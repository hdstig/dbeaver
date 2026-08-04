/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ui.editors.sql.syntax;

import org.eclipse.jface.text.contentassist.ContentAssistEvent;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.ICompletionListener;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.jkiss.code.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorUtils;
import org.jkiss.dbeaver.ui.editors.sql.SQLPreferenceConstants;

/**
 * SQL Completion proposal
 */
public class SQLContentAssistant extends ContentAssistant {

    private static final Log log = Log.getLog(SQLContentAssistant.class);

    private final SQLEditorBase editor;

    private SQLCompletionSorterUI sorter;

    private int lastCompletionOffset = - 1;
    private volatile boolean restartRequested = false;

    @Nullable
    private AssistCommandHandlerGuard assistCommandHandlerGuard;

    public SQLContentAssistant(SQLEditorBase editor) {
        super(); // Sync. Maybe we should make it async
        this.editor = editor;
        enableColoredLabels(true);
        addCompletionListener(new SessionRestartListener());
        this.assistCommandHandlerGuard = AssistCommandHandlerGuard.install(
            this,
            editor.getSite() == null ? null : editor.getSite().getWorkbenchWindow());
        startLostEndSmoke();
    }

    private static boolean lostEndSmokeStarted;

    /**
     * Harness (never committed): simulates a genuinely LOST session end event by clearing
     * ContentAssistant's completion listeners while a session is open, so hide() fires no end
     * events at all - neither the platform's restore nor the guard's own listener runs. Then
     * checks that an editor activation still repairs the handlers.
     */
    private void startLostEndSmoke() {
        if (!Boolean.getBoolean("dbeaver.test.9414.lostend") || lostEndSmokeStarted) {
            return;
        }
        lostEndSmokeStarted = true;
        String out = System.getProperty("dbeaver.test.9414.output");

        UIUtils.asyncExec(() -> UIUtils.asyncExec(() -> {
            StringBuilder logbook = new StringBuilder();
            int exitCode = 2;
            try {
                ICommandService cs = PlatformUI.getWorkbench().getService(ICommandService.class);
                var cmd = cs.getCommand("org.eclipse.ui.edit.text.goto.lineStart");
                boolean before = cmd.isHandled();
                logbook.append("handledBefore=").append(before).append("\n");

                showPossibleCompletions();
                logbook.append("handledDuringSession=").append(cmd.isHandled()).append("\n");

                // wipe every completion listener: hide() will now fire NO end event
                var f = ContentAssistant.class.getDeclaredField("fCompletionListeners");
                f.setAccessible(true);
                ((org.eclipse.core.runtime.ListenerList<?>) f.get(this)).clear();
                logbook.append("listenersCleared=true\n");
                hide();

                UIUtils.asyncExec(() -> UIUtils.asyncExec(() -> {
                    boolean afterLostEnd = cmd.isHandled();
                    logbook.append("handledAfterLostEnd=").append(afterLostEnd).append("\n");

                    // now simulate the user clicking into an editor again
                    IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                    var refs = page.getViewReferences();
                    if (refs.length > 0 && refs[0].getPart(true) != null) {
                        page.activate(refs[0].getPart(true));
                    }
                    if (page.getActiveEditor() != null) {
                        page.activate(page.getActiveEditor());
                    }
                    logbook.append("editorReactivated=true\n");

                    UIUtils.asyncExec(() -> UIUtils.asyncExec(() -> {
                        boolean recovered = cmd.isHandled();
                        logbook.append("handledAfterActivation=").append(recovered).append("\n");
                        String verdict = (before && !afterLostEnd && recovered) ? "PASS"
                            : (before && !afterLostEnd) ? "FAIL" : "ERROR";
                        try {
                            Files.writeString(Path.of(out), verdict + "\n" + logbook);
                        } catch (Exception ignored) {
                            // fall through to exit code
                        }
                        System.exit("PASS".equals(verdict) ? 0 : "FAIL".equals(verdict) ? 1 : 2);
                    }));
                }));
                return;
            } catch (Throwable e) {
                logbook.append("error=").append(e).append("\n");
            }
            try {
                Files.writeString(Path.of(out), "ERROR\n" + logbook);
            } catch (Exception ignored) {
                // nothing else to do
            }
            System.exit(exitCode);
        }));
    }

    public void setLastCompletionOffset(int lastCompletionOffset) {
        this.lastCompletionOffset = lastCompletionOffset;
    }

    /**
     * Exposes the inherited protected popup state to {@link AssistCommandHandlerGuard}, which uses
     * live popup state instead of event bookkeeping to decide whether a session is in progress.
     */
    boolean isProposalPopupCurrentlyActive() {
        return isProposalPopupActive();
    }

    @Override
    public void uninstall() {
        if (assistCommandHandlerGuard != null) {
            assistCommandHandlerGuard.dispose();
            assistCommandHandlerGuard = null;
        }
        super.uninstall();
    }

    public void setSorter(SQLCompletionSorterUI sorter) {
        this.sorter = sorter;
        super.setSorter(sorter);
    }

    public void assistSessionStarted(ContentAssistEvent event) {
        if (this.sorter != null) {
            this.sorter.refreshSettings();
        }
    }

    @Override
    protected AutoAssistListener createAutoAssistListener() {
        return new SQLAutoAssistListener();
    }

    private class SQLAutoAssistListener extends AutoAssistListener {
        @Override
        protected void showAssist(int showStyle) {
            if (showStyle == 1 && !(SQLEditorUtils.isSQLSyntaxParserApplied(editor.getEditorInput())
                && editor.getActivePreferenceStore().getBoolean(SQLPreferenceConstants.ENABLE_AUTO_ACTIVATION))
            ) {
                return;
            }
            SQLCompletionProcessor.setSimpleMode(true);
            try {
                super.showAssist(showStyle);
            } finally {
                SQLCompletionProcessor.setSimpleMode(false);
            }
        }

        @Override
        public void verifyKey(VerifyEvent event) {
            if (lastCompletionOffset >= 0 && (
                event.character == SWT.BS ||
                (event.character == 0 && event.keyCode == SWT.ARROW_LEFT)
            ) && editor.getTextViewer() != null) {
                int pos = editor.getTextViewer().getSelectedRange().x;
                if ((pos - 1) < lastCompletionOffset) {
                    restartRequested = true;
                    hide();
                    return;
                }
            }

            super.verifyKey(event);
        }
    }

    /**
     * Schedules the popup re-show requested by {@link SQLAutoAssistListener} only after the end of the
     * previous assist session has actually been observed, so that a dying session never overlaps the
     * restarted one (see #9414).
     */
    private class SessionRestartListener implements ICompletionListener {
        @Override
        public void assistSessionStarted(ContentAssistEvent event) {
            // nothing to do
        }

        @Override
        public void assistSessionEnded(ContentAssistEvent event) {
            try {
                if (restartRequested) {
                    restartRequested = false;
                    UIUtils.asyncExec(() -> showPossibleCompletions());
                }
            } catch (Throwable e) {
                // Never propagate: an exception here starves the completion listeners
                // registered after this one (see #9414)
                log.error("Error scheduling completion session restart", e);
            }
        }

        @Override
        public void selectionChanged(ICompletionProposal proposal, boolean smartToggle) {
            // nothing to do
        }
    }

    @Override
    public String showContextInformation() {
        SQLCompletionProcessor.setLookupTemplates(true);
        try {
            return super.showPossibleCompletions();
        } finally {
            SQLCompletionProcessor.setLookupTemplates(false);
        }
    }
}
