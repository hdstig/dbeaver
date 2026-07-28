/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.IHandler;
import org.eclipse.jface.text.contentassist.ContentAssistEvent;
import org.eclipse.jface.text.contentassist.ICompletionListener;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.texteditor.ITextEditorActionDefinitionIds;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ui.UIUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Watchdog for the global text navigation command handlers that
 * {@code org.eclipse.ui.texteditor.KeyBindingSupportForAssistant} nulls out for the duration of a
 * content assist session. If a single session end event is lost (a completion listener throwing
 * mid-loop, an editor disposed with the popup open, {@code ContentAssistant}'s begin/end event
 * asymmetry), the platform never restores the handlers, and because its restore logic only saves
 * handlers of commands that are currently handled, every later session cements the loss: the
 * commands stay broken until the application restarts. On macOS the fallback SWT key bindings map
 * Home/End to text start/end, which makes the breakage very visible (#9414).
 * <p>
 * The guard caches the healthy handlers of the affected commands per editor and re-installs a
 * cached handler when a command is found without any handler.
 * <p>
 * Two invariants keep the guard from misbehaving:
 * <ul>
 * <li>Whether an assist session is in progress is answered by the owning assistant's actual
 * proposal popup state, never by bookkeeping that a lost event could leave latched - the guard
 * must not be disabled by the very failure it repairs.</li>
 * <li>Command handlers belong to the editor they were captured from, so a handler cached for one
 * editor is never installed while another editor is active.</li>
 * </ul>
 */
public class AssistCommandHandlerGuard implements ICompletionListener, AssistPopupOwner {

    private static final Log log = Log.getLog(AssistCommandHandlerGuard.class);

    /**
     * The command IDs {@code KeyBindingSupportForAssistant#assistSessionStarted} sets to a
     * {@code null} handler, which is the state this guard repairs.
     * <p>
     * {@code LINE_UP} and {@code LINE_DOWN} are deliberately absent: the platform replaces those
     * with proposal-navigation handlers rather than nulling them, so a lost end event leaves them
     * non-null and this guard - which only ever replaces a missing handler - cannot tell a stale
     * proposal handler from a legitimate one. Recognising them would mean matching private
     * platform classes by name. They are left to the platform-side fix.
     */
    static final String[] GUARDED_COMMAND_IDS = {
        ITextEditorActionDefinitionIds.LINE_START,
        ITextEditorActionDefinitionIds.LINE_END,
        ITextEditorActionDefinitionIds.PAGE_UP,
        ITextEditorActionDefinitionIds.PAGE_DOWN,
        ITextEditorActionDefinitionIds.TEXT_START,
        ITextEditorActionDefinitionIds.TEXT_END,
        ITextEditorActionDefinitionIds.SCROLL_LINE_UP,
        ITextEditorActionDefinitionIds.SCROLL_LINE_DOWN,
    };

    /** How long after a session start to verify that the session was properly closed out. */
    static final int RECOVERY_CHECK_DELAY_MS = 1000;
    /** Re-check interval while the proposal popup is genuinely still open. */
    static final int RECOVERY_RECHECK_DELAY_MS = 2000;

    private static final GuardState STATE = new GuardState(new WorkbenchCommandAccess());
    private static final Set<IWorkbenchWindow> WINDOWS_WITH_PART_LISTENER =
        Collections.newSetFromMap(new WeakHashMap<>());

    private final SQLContentAssistant assistant;
    /**
     * Bumped on every session start and end, so a deferred recovery check can tell whether the
     * session it was scheduled for is still the current one.
     */
    private int sessionGeneration;

    /**
     * Installs the guard on the given assistant. Should be registered before any completion
     * listener that may fail, so that a throwing listener cannot stop the guard from seeing
     * session starts.
     */
    @Nullable
    public static AssistCommandHandlerGuard install(SQLContentAssistant assistant, @Nullable IWorkbenchWindow window) {
        try {
            AssistCommandHandlerGuard guard = new AssistCommandHandlerGuard(assistant);
            assistant.addCompletionListener(guard);
            STATE.registerPopupOwner(guard);
            installPartListener(window);
            STATE.snapshot();
            return guard;
        } catch (Throwable e) {
            log.debug("Error installing assist command handler guard", e);
            return null;
        }
    }

    private static synchronized void installPartListener(@Nullable IWorkbenchWindow window) {
        if (window == null || !WINDOWS_WITH_PART_LISTENER.add(window)) {
            return;
        }
        window.getPartService().addPartListener(new IPartListener2() {
            @Override
            public void partActivated(IWorkbenchPartReference partRef) {
                if (partRef instanceof IEditorReference) {
                    try {
                        STATE.repair();
                        STATE.snapshot();
                    } catch (Throwable e) {
                        log.debug("Error in assist command handler guard", e);
                    }
                }
            }

            @Override
            public void partClosed(IWorkbenchPartReference partRef) {
                if (partRef instanceof IEditorReference editorRef) {
                    try {
                        // Release the handlers captured for this editor, they are dead now
                        STATE.forgetPart(editorRef.getPart(false));
                    } catch (Throwable e) {
                        log.debug("Error in assist command handler guard", e);
                    }
                }
            }
        });
    }

    private AssistCommandHandlerGuard(SQLContentAssistant assistant) {
        this.assistant = assistant;
    }

    /** Stops tracking this guard; called when the owning assistant is uninstalled. */
    void dispose() {
        try {
            sessionGeneration++;
            STATE.unregisterPopupOwner(this);
        } catch (Throwable e) {
            log.debug("Error disposing assist command handler guard", e);
        }
    }

    /** Whether the owning assistant currently shows a proposal popup. */
    @Override
    public boolean isOwnPopupActive() {
        return assistant.isProposalPopupCurrentlyActive();
    }

    @Override
    public void assistSessionStarted(ContentAssistEvent event) {
        try {
            scheduleRecoveryCheck(++sessionGeneration, RECOVERY_CHECK_DELAY_MS);
        } catch (Throwable e) {
            // Never propagate: an exception here starves the completion listeners
            // registered after this one (see #9414)
            log.debug("Error in assist command handler guard", e);
        }
    }

    @Override
    public void assistSessionEnded(ContentAssistEvent event) {
        try {
            // Invalidates the recovery check scheduled for the session that just ended cleanly
            sessionGeneration++;
            // Deferred so that KeyBindingSupportForAssistant's own restore, which runs after this
            // listener in the same event loop, gets the first chance to put the handlers back
            UIUtils.asyncExec(() -> {
                STATE.repair();
                STATE.snapshot();
            });
        } catch (Throwable e) {
            log.debug("Error in assist command handler guard", e);
        }
    }

    @Override
    public void selectionChanged(ICompletionProposal proposal, boolean smartToggle) {
        // nothing to do
    }

    /**
     * Verifies after a delay that the session started with {@code generation} was closed out.
     * This is what recovers a session whose end event never arrived at all, without waiting for
     * another assist session or an editor activation.
     */
    private void scheduleRecoveryCheck(int generation, int delay) {
        UIUtils.timerExec(delay, () -> {
            try {
                if (generation != sessionGeneration) {
                    // The session ended, or a newer one started: this check is stale
                    return;
                }
                if (isOwnPopupActive()) {
                    // The popup really is still open, so the handlers are meant to be replaced
                    scheduleRecoveryCheck(generation, RECOVERY_RECHECK_DELAY_MS);
                    return;
                }
                // Popup gone but no end event ever arrived: the handlers may have been left nulled
                STATE.repair();
                STATE.snapshot();
            } catch (Throwable e) {
                log.debug("Error in assist command handler guard", e);
            }
        });
    }

    /**
     * The snapshot/repair state machine, separated from the workbench through
     * {@link CommandAccess} for unit testing. All methods must be called on the UI thread.
     */
    static class GuardState {
        private final CommandAccess commands;
        /** Healthy handlers per editor part; weakly keyed so closed editors are released. */
        private final Map<Object, Map<String, IHandler>> knownHandlers = new WeakHashMap<>();
        private final Set<AssistPopupOwner> popupOwners = Collections.newSetFromMap(new WeakHashMap<>());

        GuardState(CommandAccess commands) {
            this.commands = commands;
        }

        void registerPopupOwner(AssistPopupOwner owner) {
            popupOwners.add(owner);
        }

        void unregisterPopupOwner(AssistPopupOwner owner) {
            popupOwners.remove(owner);
        }

        /**
         * Whether any assistant currently shows a proposal popup. Derived from live UI state on
         * every call, so no lost event can leave it stuck reporting {@code true}.
         */
        boolean isSessionActive() {
            for (AssistPopupOwner owner : popupOwners) {
                try {
                    if (owner.isOwnPopupActive()) {
                        return true;
                    }
                } catch (Throwable e) {
                    // A broken owner must not make the guard believe a session is running
                    log.debug("Error querying proposal popup state", e);
                }
            }
            return false;
        }

        /** Caches the current handler of every guarded command for the active editor. */
        void snapshot() {
            if (isSessionActive() || !commands.isAvailable()) {
                return;
            }
            Object part = commands.activePartKey();
            if (part == null) {
                return;
            }
            Map<String, IHandler> forPart = knownHandlers.computeIfAbsent(part, k -> new HashMap<>());
            for (String commandId : GUARDED_COMMAND_IDS) {
                if (!commands.isHandled(commandId)) {
                    continue;
                }
                IHandler handler = commands.getHandler(commandId);
                if (handler != null && handler != forPart.get(commandId)) {
                    forPart.put(commandId, handler);
                }
            }
        }

        /**
         * Re-installs the handlers cached for the active editor on guarded commands that lost
         * theirs. Handlers captured for a different editor are never installed: the workbench
         * scopes them to the part that was active when they were current.
         */
        void repair() {
            if (isSessionActive() || !commands.isAvailable()) {
                return;
            }
            Object part = commands.activePartKey();
            if (part == null) {
                return;
            }
            Map<String, IHandler> forPart = knownHandlers.get(part);
            if (forPart == null) {
                return;
            }
            for (String commandId : GUARDED_COMMAND_IDS) {
                if (commands.getHandler(commandId) != null) {
                    // Only handler == null is the #9414 poisoning; never overwrite a live
                    // handler, even a foreign one that reports isHandled() == false
                    continue;
                }
                IHandler saved = forPart.get(commandId);
                if (saved != null) {
                    commands.setHandler(commandId, saved);
                    log.warn("Restored lost handler for '" + commandId + "' (see dbeaver#9414)");
                }
            }
        }

        /** Releases the handlers cached for an editor that is going away. */
        void forgetPart(@Nullable Object part) {
            if (part != null) {
                knownHandlers.remove(part);
            }
        }
    }

    /** Minimal workbench facade, so that {@link GuardState} needs no running workbench. */
    interface CommandAccess {
        boolean isAvailable();

        boolean isHandled(String commandId);

        @Nullable
        IHandler getHandler(String commandId);

        void setHandler(String commandId, IHandler handler);

        /** Identity of the currently active editor, or {@code null} if there is none. */
        @Nullable
        Object activePartKey();
    }

    private static class WorkbenchCommandAccess implements CommandAccess {
        @Override
        public boolean isAvailable() {
            return getCommandService() != null;
        }

        @Override
        public boolean isHandled(String commandId) {
            Command command = getCommand(commandId);
            return command != null && command.isHandled();
        }

        @Nullable
        @Override
        public IHandler getHandler(String commandId) {
            Command command = getCommand(commandId);
            return command == null ? null : command.getHandler();
        }

        @Override
        public void setHandler(String commandId, IHandler handler) {
            Command command = getCommand(commandId);
            if (command != null) {
                command.setHandler(handler);
            }
        }

        @Nullable
        @Override
        public Object activePartKey() {
            if (!PlatformUI.isWorkbenchRunning()) {
                return null;
            }
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            IWorkbenchPage page = window == null ? null : window.getActivePage();
            IEditorPart editor = page == null ? null : page.getActiveEditor();
            return editor;
        }

        @Nullable
        private static Command getCommand(String commandId) {
            ICommandService commandService = getCommandService();
            return commandService == null ? null : commandService.getCommand(commandId);
        }

        @Nullable
        private static ICommandService getCommandService() {
            return PlatformUI.isWorkbenchRunning()
                ? PlatformUI.getWorkbench().getService(ICommandService.class)
                : null;
        }
    }
}
