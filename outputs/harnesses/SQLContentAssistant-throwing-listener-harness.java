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

    private static boolean issue9414SmokeStarted;

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
        startIssue9414Smoke();
    }

    private void startIssue9414Smoke() {
        if (!Boolean.getBoolean("dbeaver.test.9414") || issue9414SmokeStarted) {
            return;
        }
        issue9414SmokeStarted = true;

        var listener = new ICompletionListener() {
            boolean started;
            boolean threw;

            @Override
            public void assistSessionStarted(ContentAssistEvent event) {
                started = true;
            }

            @Override
            public void assistSessionEnded(ContentAssistEvent event) {
                if (!threw) {
                    threw = true;
                    throw new RuntimeException("Injected dbeaver#9414 completion-listener failure");
                }
            }

            @Override
            public void selectionChanged(ICompletionProposal proposal, boolean smartToggle) {
            }
        };
        addCompletionListener(listener);

        UIUtils.asyncExec(() -> UIUtils.asyncExec(() -> {
            ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
            var command = commandService.getCommand("org.eclipse.ui.edit.text.goto.lineStart");
            boolean handledBefore = command.isHandled();
            String completionError = null;
            Throwable hideError = null;
            try {
                completionError = showPossibleCompletions();
                hide();
            } catch (Throwable e) {
                hideError = e;
            }

            String finalCompletionError = completionError;
            Throwable finalHideError = hideError;
            UIUtils.asyncExec(() -> UIUtils.asyncExec(() -> {
                boolean handledAfter = command.isHandled();
                String result;
                int exitCode;
                if (!listener.started || !listener.threw || !handledBefore) {
                    result = "ERROR";
                    exitCode = 2;
                } else if (handledAfter) {
                    result = "PASS";
                    exitCode = 0;
                } else {
                    result = "FAIL";
                    exitCode = 1;
                }

                String report = result + "\n"
                    + "sessionStarted=" + listener.started + "\n"
                    + "listenerThrew=" + listener.threw + "\n"
                    + "handledBefore=" + handledBefore + "\n"
                    + "handledAfter=" + handledAfter + "\n"
                    + "completionError=" + finalCompletionError + "\n"
                    + "hideError=" + finalHideError + "\n";
                try {
                    Files.writeString(Path.of(System.getProperty("dbeaver.test.9414.output")), report);
                } catch (Exception e) {
                    exitCode = 2;
                }
                System.exit(exitCode);
            }));
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
