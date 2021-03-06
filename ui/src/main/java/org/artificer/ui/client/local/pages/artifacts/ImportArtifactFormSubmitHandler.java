/*
 * Copyright 2013 JBoss Inc
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
package org.artificer.ui.client.local.pages.artifacts;

import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.FormPanel.SubmitCompleteEvent;
import com.google.gwt.user.client.ui.FormPanel.SubmitCompleteHandler;
import com.google.gwt.user.client.ui.FormPanel.SubmitEvent;
import com.google.gwt.user.client.ui.FormPanel.SubmitHandler;
import com.google.gwt.user.client.ui.InlineLabel;
import com.google.gwt.user.client.ui.Widget;
import org.artificer.ui.client.local.ClientMessages;
import org.artificer.ui.client.local.pages.ArtifactDetailsPage;
import org.artificer.ui.client.local.services.NotificationService;
import org.artificer.ui.client.local.util.IUploadCompletionHandler;
import org.artificer.ui.client.shared.beans.NotificationBean;
import org.jboss.errai.ui.nav.client.local.TransitionAnchor;
import org.jboss.errai.ui.nav.client.local.TransitionAnchorFactory;
import org.overlord.commons.gwt.client.local.widgets.ModalDialog;

import javax.enterprise.context.Dependent;
import javax.inject.Inject;

/**
 * The form submit handler used by the {@link ImportArtifactDialog}.
 *
 * @author eric.wittmann@redhat.com
 */
@Dependent
public class ImportArtifactFormSubmitHandler implements SubmitHandler, SubmitCompleteHandler {

    @Inject
    protected ClientMessages i18n;
    @Inject
    private NotificationService notificationService;
    @Inject
    private TransitionAnchorFactory<ArtifactDetailsPage> toDetailsFactory;

    private ModalDialog dialog;
    private NotificationBean notification;
    private IUploadCompletionHandler completionHandler;

    /**
     * Constructor.
     */
    public ImportArtifactFormSubmitHandler() {
    }

    /**
     * @param dialog
     */
    public void setDialog(ModalDialog dialog) {
        this.dialog = dialog;
    }

    /**
     * @see com.google.gwt.user.client.ui.FormPanel.SubmitHandler#onSubmit(com.google.gwt.user.client.ui.FormPanel.SubmitEvent)
     */
    @Override
    public void onSubmit(SubmitEvent event) {
        dialog.hide(false);
        notification = notificationService.startProgressNotification(
                i18n.format("import-artifact-submit.importing.title"), //$NON-NLS-1$
                i18n.format("import-artifact-submit.importing.msg")); //$NON-NLS-1$
    }


    /**
     * @see com.google.gwt.user.client.ui.FormPanel.SubmitCompleteHandler#onSubmitComplete(com.google.gwt.user.client.ui.FormPanel.SubmitCompleteEvent)
     */
    @Override
    public void onSubmitComplete(SubmitCompleteEvent event) {
        dialog.destroy();

        ArtifactUploadResult results = ArtifactUploadResult.fromResult(event.getResults());
        if (results.isError()) {
            if (results.getError() != null) {
                notificationService.completeProgressNotification(
                        notification.getUuid(),
                        i18n.format("import-artifact-submit.import-error.title"), //$NON-NLS-1$
                        results.getError());
            } else {
                notificationService.completeProgressNotification(
                        notification.getUuid(),
                        i18n.format("import-artifact-submit.import-error.title"), //$NON-NLS-1$
                        i18n.format("import-artifact-submit.import-error.msg")); //$NON-NLS-1$
            }
        } else if (results.isBatch()) {
            String message = i18n.format(
                    "import-artifact-submit.import-success.msg",  //$NON-NLS-1$
                    results.getBatchNumSuccess(),
                    results.getBatchNumFailed());
            notificationService.completeProgressNotification(
                    notification.getUuid(),
                    i18n.format("import-artifact-submit.import-batch-success.title"), //$NON-NLS-1$
                    message);
        } else {
            Widget ty = new InlineLabel(i18n.format("import-artifact-submit.import-complete.msg")); //$NON-NLS-1$
            TransitionAnchor<ArtifactDetailsPage> clickHere = toDetailsFactory.get("uuid", results.getUuid()); //$NON-NLS-1$
            clickHere.setText(i18n.format("import-artifact-submit.click-here-1")); //$NON-NLS-1$
            Widget postAmble = new InlineLabel(i18n.format("import-artifact-submit.click-here-2")); //$NON-NLS-1$
            FlowPanel body = new FlowPanel();
            body.add(ty);
            body.add(clickHere);
            body.add(postAmble);
            notificationService.completeProgressNotification(
                    notification.getUuid(),
                    i18n.format("import-artifact-submit.import-complete.title"), //$NON-NLS-1$
                    body);
            if (completionHandler != null) {
                completionHandler.onImportComplete();
            }
        }
    }

    /**
     * @return the completionHandler
     */
    public IUploadCompletionHandler getCompletionHandler() {
        return completionHandler;
    }

    /**
     * @param completionHandler the completionHandler to set
     */
    public void setCompletionHandler(IUploadCompletionHandler completionHandler) {
        this.completionHandler = completionHandler;
    }
}
