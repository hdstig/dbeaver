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
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.ICompletionListener;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
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
 * {@code org.eclipse.ui.texteditor.KeyBindingSupportForAssistant} swaps out for the duration of a
 * content assist session. If a single session end event is lost (a completion listener throwing
 * mid-loop, an editor disposed with the popup open, {@code ContentAssistant}'s begin/end event
 * asymmetry), the platform never restores the handlers, and because its restore logic only saves
 * handlers of commands that are currently handled, every later session cements the loss: the
 * commands stay broken until the application restarts. On macOS the fallback SWT key bindings map
 * Home/End to text start/end, which makes the breakage very visible (#9414).
 * <p>
 * The guard caches the healthy handlers of the affected commands and re-installs a cached handler
 * when a command is found without any handler while no assist session is active.
 */
public class AssistCommandHandlerGuard implements ICompletionListener {

    private static final Log log = Log.getLog(AssistCommandHandlerGuard.class);

    /** The command IDs replaced by {@code KeyBindingSupportForAssistant#assistSessionStarted}. */
    static final String[] GUARDED_COMMAND_IDS = {
        ITextEditorActionDefinitionIds.LINE_UP,
        ITextEditorActionDefinitionIds.LINE_DOWN,
        ITextEditorActionDefinitionIds.LINE_START,
        ITextEditorActionDefinitionIds.LINE_END,
        ITextEditorActionDefinitionIds.PAGE_UP,
        ITextEditorActionDefinitionIds.PAGE_DOWN,
        ITextEditorActionDefinitionIds.TEXT_START,
        ITextEditorActionDefinitionIds.TEXT_END,
        ITextEditorActionDefinitionIds.SCROLL_LINE_UP,
        ITextEditorActionDefinitionIds.SCROLL_LINE_DOWN,
    };

    private static final GuardState STATE = new GuardState(new WorkbenchCommandAccess());
    private static final Set<IWorkbenchWindow> WINDOWS_WITH_PART_LISTENER =
        Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Installs the guard on the given assistant. Must be registered before any completion
     * listener that may fail, so that the active session accounting stays accurate even when a
     * later listener throws.
     */
    public static void install(ContentAssistant assistant, @Nullable IWorkbenchWindow window) {
        try {
            assistant.addCompletionListener(new AssistCommandHandlerGuard());
            installPartListener(window);
            STATE.snapshot();
        } catch (Throwable e) {
            log.debug("Error installing assist command handler guard", e);
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
        });
    }

    private AssistCommandHandlerGuard() {
    }

    @Override
    public void assistSessionStarted(ContentAssistEvent event) {
        try {
            STATE.sessionStarted();
        } catch (Throwable e) {
            // Never propagate: an exception here starves the completion listeners
            // registered after this one (see #9414)
            log.debug("Error in assist command handler guard", e);
        }
    }

    @Override
    public void assistSessionEnded(ContentAssistEvent event) {
        try {
            STATE.sessionEnded();
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
     * The snapshot/repair state machine, separated from the workbench through
     * {@link CommandAccess} for unit testing. All methods must be called on the UI thread.
     */
    static class GuardState {
        private final CommandAccess commands;
        private final Map<String, IHandler> knownHandlers = new HashMap<>();
        private int activeSessions;

        GuardState(CommandAccess commands) {
            this.commands = commands;
        }

        void sessionStarted() {
            activeSessions++;
        }

        void sessionEnded() {
            if (activeSessions > 0) {
                activeSessions--;
            }
        }

        boolean isSessionActive() {
            return activeSessions > 0;
        }

        /** Caches the current handler of every guarded command that is currently handled. */
        void snapshot() {
            if (isSessionActive() || !commands.isAvailable()) {
                return;
            }
            for (String commandId : GUARDED_COMMAND_IDS) {
                if (!commands.isHandled(commandId)) {
                    continue;
                }
                IHandler handler = commands.getHandler(commandId);
                if (handler != null && handler != knownHandlers.get(commandId)) {
                    knownHandlers.put(commandId, handler);
                }
            }
        }

        /** Re-installs the cached handler on every guarded command whose handler got lost. */
        void repair() {
            if (isSessionActive() || !commands.isAvailable()) {
                return;
            }
            for (String commandId : GUARDED_COMMAND_IDS) {
                if (commands.getHandler(commandId) != null) {
                    // Only handler == null is the #9414 poisoning; never overwrite a live
                    // handler, even a foreign one that reports isHandled() == false
                    continue;
                }
                IHandler saved = knownHandlers.get(commandId);
                if (saved != null) {
                    commands.setHandler(commandId, saved);
                    log.warn("Restored lost handler for '" + commandId + "' (see dbeaver#9414)");
                }
            }
        }
    }

    /** Minimal command service facade, so that {@link GuardState} needs no running workbench. */
    interface CommandAccess {
        boolean isAvailable();

        boolean isHandled(String commandId);

        @Nullable
        IHandler getHandler(String commandId);

        void setHandler(String commandId, IHandler handler);
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
