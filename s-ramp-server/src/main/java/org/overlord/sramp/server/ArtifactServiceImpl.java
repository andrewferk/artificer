/*
 * Copyright 2014 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.overlord.sramp.server;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.oasis_open.docs.s_ramp.ns.s_ramp_v1.BaseArtifactEnum;
import org.oasis_open.docs.s_ramp.ns.s_ramp_v1.BaseArtifactType;
import org.oasis_open.docs.s_ramp.ns.s_ramp_v1.DocumentArtifactType;
import org.overlord.sramp.atom.visitors.ArtifactContentTypeVisitor;
import org.overlord.sramp.common.ArtifactContent;
import org.overlord.sramp.common.ArtifactType;
import org.overlord.sramp.common.ArtifactTypeEnum;
import org.overlord.sramp.common.ArtifactVerifier;
import org.overlord.sramp.common.SrampModelUtils;
import org.overlord.sramp.common.error.ArtifactNotFoundException;
import org.overlord.sramp.common.error.ContentNotFoundException;
import org.overlord.sramp.common.error.InvalidArtifactCreationException;
import org.overlord.sramp.common.visitors.ArtifactVisitorHelper;
import org.overlord.sramp.events.EventProducer;
import org.overlord.sramp.events.EventProducerFactory;
import org.overlord.sramp.integration.ArchiveContext;
import org.overlord.sramp.integration.ExtensionFactory;
import org.overlord.sramp.repository.PersistenceManager;
import org.overlord.sramp.repository.error.DerivedArtifactCreateException;
import org.overlord.sramp.repository.error.DerivedArtifactDeleteException;
import org.overlord.sramp.server.core.api.ArtifactService;
import org.overlord.sramp.server.i18n.Messages;
import org.overlord.sramp.server.mime.MimeTypes;

import javax.ejb.Remote;
import javax.ejb.Stateful;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.StreamingOutput;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Set;

/**
 * @author Brett Meyer.
 */
@Stateful(name = "ArtifactService")
@Remote(ArtifactService.class)
public class ArtifactServiceImpl extends AbstractServiceImpl implements ArtifactService {

    @Override
    public BaseArtifactType create(BaseArtifactType artifact) throws Exception {
        ArtifactType artifactType = ArtifactType.valueOf(artifact);

        ArtifactVerifier verifier = new ArtifactVerifier(artifactType);
        ArtifactVisitorHelper.visitArtifact(verifier, artifact);
        verifier.throwError();

        if (artifactType.isDerived()) {
            throw new DerivedArtifactCreateException(artifactType.getArtifactType());
        }
        if (artifactType.isDocument()) {
            throw new InvalidArtifactCreationException(Messages.i18n.format("INVALID_DOCARTY_CREATE")); //$NON-NLS-1$
        }

        PersistenceManager persistenceManager = persistenceManager();
        // store the content
        BaseArtifactType persistedArtifact = persistenceManager.persistArtifact(artifact, null);

        Set<EventProducer> eventProducers = EventProducerFactory.getEventProducers();
        for (EventProducer eventProducer : eventProducers) {
            eventProducer.artifactCreated(persistedArtifact);
        }

        return persistedArtifact;
    }

    @Override
    public BaseArtifactType upload(String model, String type, String fileName, InputStream is)
            throws Exception {
        ArtifactType artifactType = ArtifactType.valueOf(model, type, true);
        return upload(artifactType, fileName, is);
    }

    @Override
    public BaseArtifactType upload(String fileName, InputStream is) throws Exception {
        return upload(null, fileName, is);
    }

    @Override
    public BaseArtifactType upload(ArtifactType artifactType, String fileName, InputStream is)
            throws Exception {
        // Pick a reasonable file name if Slug is not present
        if (fileName == null) {
            if (artifactType.getArtifactType() == ArtifactTypeEnum.Document) {
                fileName = "newartifact.bin"; //$NON-NLS-1$
            } else if (artifactType.getArtifactType() == ArtifactTypeEnum.XmlDocument) {
                fileName = "newartifact.xml"; //$NON-NLS-1$
            } else {
                fileName = "newartifact." + artifactType.getArtifactType().getModel(); //$NON-NLS-1$
            }
        }

        ArtifactContent content = null;
        ArchiveContext archiveContext = null;

        try {
            content = new ArtifactContent(fileName, is);
            if (ExtensionFactory.isArchive(content)) {
                archiveContext = ArchiveContext.createArchiveContext(content);

                if (artifactType == null) {
                    artifactType = ExtensionFactory.detect(content, archiveContext);
                }
            } else {
                if (artifactType == null) {
                    artifactType = ExtensionFactory.detect(content);
                }
            }

            String mimeType = MimeTypes.determineMimeType(fileName, content.getInputStream(), artifactType);
            artifactType.setMimeType(mimeType);

            BaseArtifactType artifact = artifactType.newArtifactInstance();
            artifact.setName(fileName);

            if (archiveContext != null) {
                // If it's an archive, expand it and upload through a batch (necessary for adequate relationship processing).

                // First, create the archive artifact's metadata.  At least the UUID is necessary for the
                // expandedFromDocument relationship.
                PersistenceManager persistenceManager = persistenceManager();
                artifact = persistenceManager.persistArtifact(artifact, null);

                // Then, expand (building up a batch).
                BatchCreate creates = new BatchCreate();
                // Set the artifact in the context for the type detectors to use.
                archiveContext.setArchiveArtifactType(artifactType);
                Collection<File> subFiles = archiveContext.expand();
                for (File subFile : subFiles) {
                    String pathInArchive = archiveContext.stripWorkDir(subFile.getAbsolutePath());
                    ArtifactContent subArtifactContent = new ArtifactContent(pathInArchive, subFile);
                    if (ExtensionFactory.allowExpansionFromArchive(subArtifactContent, archiveContext)) {
                        ArtifactType subArtifactType = ExtensionFactory.detect(subArtifactContent, archiveContext);
                        // detectors do not accept everything...
                        if (subArtifactType != null) {
                            String subMimeType = MimeTypes.determineMimeType(subFile.getName(),
                                    subArtifactContent.getInputStream(), subArtifactType);
                            subArtifactType.setMimeType(subMimeType);

                            BaseArtifactType subArtifact = subArtifactType.newArtifactInstance();
                            subArtifact.setName(subFile.getName());

                            // set relevant properties/relationships
                            SrampModelUtils.setCustomProperty(subArtifact, "expanded.from.archive.path", pathInArchive);
                            SrampModelUtils.addGenericRelationship(subArtifact, "expandedFromDocument", artifact.getUuid());

                            creates.add(subArtifact, subArtifactContent, subArtifactContent.getPath());
                        }
                    }
                }
                // Persist the batch.
                creates.execute(persistenceManager());

                // Finally, update the archive artifact's content.
                artifact = persistenceManager.updateArtifactContent(artifact.getUuid(),
                        archiveContext.getArchiveArtifactType(), content);
            } else {
                // Else, simple upload.
                artifact = doUpload(artifact, content, artifactType);
            }

            return artifact;
        } finally {
            if (content != null) {
                content.cleanup();
            }
            if (archiveContext != null) {
                archiveContext.cleanup();
            }
        }
    }

    private BaseArtifactType doUpload(BaseArtifactType artifact, ArtifactContent content,
            ArtifactType artifactType) throws Exception {
        if (artifactType == null) {
            // Early exit.  No detector wanted it, and we don't return general Documents.
            return null;
        }

        if (artifactType.isDerived()) {
            throw new DerivedArtifactCreateException(artifactType.getArtifactType());
        }

        PersistenceManager persistenceManager = persistenceManager();
        // store the content
        if (!SrampModelUtils.isDocumentArtifact(artifact)) {
            throw new InvalidArtifactCreationException(Messages.i18n.format("INVALID_DOCARTY_CREATE")); //$NON-NLS-1$
        }

        artifact = persistenceManager.persistArtifact(artifact, content);

        Set<EventProducer> eventProducers = EventProducerFactory.getEventProducers();
        for (EventProducer eventProducer : eventProducers) {
            eventProducer.artifactCreated(artifact);
        }

        return artifact;
    }

    @Override
    public void updateMetaData(String model, String type, String uuid, BaseArtifactType updatedArtifact)
            throws Exception {
        ArtifactType artifactType = ArtifactType.valueOf(model, type, null);
        updateMetaData(artifactType, uuid, updatedArtifact);
    }

    @Override
    public void updateMetaData(ArtifactType artifactType, String uuid,
            BaseArtifactType updatedArtifact) throws Exception {
        PersistenceManager persistenceManager = persistenceManager();
        BaseArtifactType oldArtifact = persistenceManager.getArtifact(uuid, artifactType);
        if (oldArtifact == null) {
            throw new ArtifactNotFoundException(uuid);
        }

        ArtifactVerifier verifier = new ArtifactVerifier(oldArtifact, artifactType);
        ArtifactVisitorHelper.visitArtifact(verifier, updatedArtifact);
        verifier.throwError();

        updatedArtifact = persistenceManager.updateArtifact(updatedArtifact, artifactType);

        Set<EventProducer> eventProducers = EventProducerFactory.getEventProducers();
        for (EventProducer eventProducer : eventProducers) {
            eventProducer.artifactUpdated(updatedArtifact, oldArtifact);
        }
    }

    @Override
    public void updateContent(String model, String type, String uuid, String fileName, InputStream is)
            throws Exception {
        ArtifactType artifactType = ArtifactType.valueOf(model, type, null);
        updateContent(artifactType, uuid, fileName, is);
    }

    @Override
    public void updateContent(ArtifactType artifactType, String uuid,
            String fileName, InputStream is) throws Exception {
        if (artifactType.isDerived()) {
            throw new DerivedArtifactCreateException(artifactType.getArtifactType());
        }
        String mimeType = MimeTypes.determineMimeType(fileName, is, artifactType);
        artifactType.setMimeType(mimeType);

        // TODO we need to update the S-RAMP metadata too (new updateDate, size, etc)?

        PersistenceManager persistenceManager = persistenceManager();
        BaseArtifactType oldArtifact = persistenceManager.getArtifact(uuid, artifactType);
        if (oldArtifact == null) {
            throw new ArtifactNotFoundException(uuid);
        }
        ArtifactContent content = new ArtifactContent(fileName, is);
        BaseArtifactType updatedArtifact = persistenceManager.updateArtifactContent(uuid, artifactType, content);

        Set<EventProducer> eventProducers = EventProducerFactory.getEventProducers();
        for (EventProducer eventProducer : eventProducers) {
            eventProducer.artifactUpdated(updatedArtifact, oldArtifact);
        }
    }

    @Override
    public BaseArtifactType getMetaData(String model, String type, String uuid) throws Exception {
        ArtifactType artifactType = ArtifactType.valueOf(model, type, false);
        return getMetaData(artifactType, uuid);
    }

    @Override
    public BaseArtifactType getMetaData(ArtifactType artifactType, String uuid) throws Exception {
        PersistenceManager persistenceManager = persistenceManager();

        // Get the artifact by UUID
        // TODO: The last extendedDocFix check should not be necessary.  However, since we
        // don't know whether or not the artifact has content prior to calling ArtifactType.valueOf, this is
        // necessary.  It would be better if we could somehow get the artifact without knowing the artifact type
        // ahead of time (ie, purely use the JCR property).
        BaseArtifactType artifact = persistenceManager.getArtifact(uuid, artifactType);
        if (artifact == null || (!artifactType.getArtifactType().getApiType().equals(artifact.getArtifactType())
                && !(artifactType.getArtifactType().equals(ArtifactTypeEnum.ExtendedArtifactType) && artifact.getArtifactType().equals(BaseArtifactEnum.EXTENDED_DOCUMENT)))) {
            throw new ArtifactNotFoundException(uuid);
        }

        return artifact;
    }

    @Override
    public Object getContent(String model, String type, String uuid) throws Exception {
        ArtifactType artifactType = ArtifactType.valueOf(model, type, true);
        BaseArtifactType artifact = getMetaData(artifactType, uuid);
        return getContent(artifactType, artifact);
    }

    @Override
    public Object getContent(ArtifactType artifactType, String uuid) throws Exception {
        BaseArtifactType artifact = getMetaData(artifactType, uuid);
        return getContent(artifactType, artifact);
    }

    @Override
    public Object getContent(ArtifactType artifactType, BaseArtifactType artifact) throws Exception {
        if (!(artifact instanceof DocumentArtifactType)) {
            throw new ContentNotFoundException(artifact.getUuid());
        }
        DocumentArtifactType documentArtifact = (DocumentArtifactType) artifact;
        if (documentArtifact.getContentSize() == 0  || StringUtils.isEmpty(documentArtifact.getContentHash())) {
            throw new ContentNotFoundException(artifact.getUuid());
        }

        PersistenceManager persistenceManager = persistenceManager();

        ArtifactContentTypeVisitor ctVizzy = new ArtifactContentTypeVisitor();
        ArtifactVisitorHelper.visitArtifact(ctVizzy, artifact);
        javax.ws.rs.core.MediaType mediaType = ctVizzy.getContentType();
        artifactType.setMimeType(mediaType.toString());
        final InputStream artifactContent = persistenceManager.getArtifactContent(artifact.getUuid(), artifactType);
        Object output = new StreamingOutput() {
            @Override
            public void write(OutputStream output) throws IOException, WebApplicationException {
                try {
                    IOUtils.copy(artifactContent, output);
                } finally {
                    IOUtils.closeQuietly(artifactContent);
                }
            }
        };
        return output;
    }

    @Override
    public void delete(String model, String type, String uuid) throws Exception {
        ArtifactType artifactType = ArtifactType.valueOf(model, type, null);
        delete(artifactType, uuid);
    }

    @Override
    public void delete(ArtifactType artifactType, String uuid) throws Exception {
        if (artifactType.isDerived()) {
            throw new DerivedArtifactDeleteException(artifactType.getArtifactType());
        }

        PersistenceManager persistenceManager = persistenceManager();
        // Delete the artifact by UUID
        BaseArtifactType artifact = persistenceManager.deleteArtifact(uuid, artifactType);

        Set<EventProducer> eventProducers = EventProducerFactory.getEventProducers();
        for (EventProducer eventProducer : eventProducers) {
            eventProducer.artifactDeleted(artifact);
        }
    }

    @Override
    public void deleteContent(String model, String type, String uuid) throws Exception {
        ArtifactType artifactType = ArtifactType.valueOf(model, type, null);
        deleteContent(artifactType, uuid);
    }

    @Override
    public void deleteContent(ArtifactType artifactType, String uuid) throws Exception {
        if (artifactType.isDerived()) {
            throw new DerivedArtifactDeleteException(artifactType.getArtifactType());
        }

        PersistenceManager persistenceManager = persistenceManager();

        BaseArtifactType oldArtifact = persistenceManager.getArtifact(uuid, artifactType);
        if (oldArtifact == null) {
            throw new ArtifactNotFoundException(uuid);
        }

        // Delete the artifact content
        BaseArtifactType updatedArtifact = persistenceManager.deleteArtifactContent(uuid, artifactType);

        Set<EventProducer> eventProducers = EventProducerFactory.getEventProducers();
        for (EventProducer eventProducer : eventProducers) {
            eventProducer.artifactUpdated(updatedArtifact, oldArtifact);
        }
    }
}
