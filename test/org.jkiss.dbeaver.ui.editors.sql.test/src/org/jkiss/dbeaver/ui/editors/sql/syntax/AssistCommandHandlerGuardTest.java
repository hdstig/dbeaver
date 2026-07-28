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

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.IHandler;
import org.eclipse.ui.texteditor.ITextEditorActionDefinitionIds;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests for the {@link AssistCommandHandlerGuard.GuardState} snapshot/repair state machine
 * (see #9414). Uses a fake {@link AssistCommandHandlerGuard.CommandAccess} and fake popup
 * owners, so no workbench is needed.
 */
public class AssistCommandHandlerGuardTest {

    private static final String LINE_START = ITextEditorActionDefinitionIds.LINE_START;
    private static final String LINE_END = ITextEditorActionDefinitionIds.LINE_END;

    /** Stand-ins for editor parts; the guard keys cached handlers on part identity. */
    private static final Object EDITOR_A = new Object();
    private static final Object EDITOR_B = new Object();

    private static class FakeHandler extends AbstractHandler {
        private final boolean handled;

        FakeHandler() {
            this(true);
        }

        FakeHandler(boolean handled) {
            this.handled = handled;
        }

        @Override
        public boolean isHandled() {
            return handled;
        }

        @Override
        public Object execute(ExecutionEvent event) {
            return null;
        }
    }

    /** Fake assist session: reports popup state the way a real assistant would. */
    private static class FakePopup implements AssistPopupOwner {
        private boolean popupActive;
        private boolean broken;

        @Override
        public boolean isOwnPopupActive() {
            if (broken) {
                throw new IllegalStateException("assistant is broken");
            }
            return popupActive;
        }
    }

    private static class FakeCommandAccess implements AssistCommandHandlerGuard.CommandAccess {
        private final Map<String, IHandler> handlers = new HashMap<>();
        private boolean available = true;
        private Object activePart = EDITOR_A;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public boolean isHandled(String commandId) {
            IHandler handler = handlers.get(commandId);
            return handler != null && handler.isHandled();
        }

        @Override
        public IHandler getHandler(String commandId) {
            return handlers.get(commandId);
        }

        @Override
        public void setHandler(String commandId, IHandler handler) {
            handlers.put(commandId, handler);
        }

        @Override
        public Object activePartKey() {
            return activePart;
        }
    }

    private FakeCommandAccess commands;
    private AssistCommandHandlerGuard.GuardState state;
    private FakePopup session;
    private IHandler lineStartHandler;
    private IHandler lineEndHandler;

    @BeforeEach
    public void setUp() {
        commands = new FakeCommandAccess();
        state = new AssistCommandHandlerGuard.GuardState(commands);
        session = new FakePopup();
        state.registerPopupOwner(session);
        lineStartHandler = new FakeHandler();
        lineEndHandler = new FakeHandler();
        commands.setHandler(LINE_START, lineStartHandler);
        commands.setHandler(LINE_END, lineEndHandler);
    }

    /** Simulates KeyBindingSupportForAssistant nulling the handlers at session start. */
    private void platformNullsHandlers() {
        for (String commandId : AssistCommandHandlerGuard.GUARDED_COMMAND_IDS) {
            commands.setHandler(commandId, null);
        }
    }

    @Test
    public void healthySnapshotIsRestoredAfterStarvedRestore() {
        state.snapshot();

        session.popupActive = true;
        platformNullsHandlers();
        session.popupActive = false;
        // the platform restore was starved (a listener threw): handlers stay null

        state.repair();

        Assertions.assertSame(lineStartHandler, commands.getHandler(LINE_START));
        Assertions.assertSame(lineEndHandler, commands.getHandler(LINE_END));
    }

    /**
     * Regression for the defect that shipped: session state must come from live popup state, so a
     * session whose end event never arrives cannot leave the guard permanently disabled. No
     * further session and no part activation is needed - closing the popup is enough.
     */
    @Test
    public void lostEndEventIsRepairedOnceThePopupIsGone() {
        state.snapshot();

        session.popupActive = true;
        platformNullsHandlers();

        state.repair();
        Assertions.assertNull(commands.getHandler(LINE_START), "the popup is still open");

        // No end event is ever delivered; the popup simply goes away
        session.popupActive = false;
        state.repair();

        Assertions.assertSame(lineStartHandler, commands.getHandler(LINE_START),
            "a lost end event must not leave the guard disabled");
    }

    /** An open popup stays "active" no matter how much time passes: no timeout heuristic. */
    @Test
    public void popupOpenIndefinitelyIsNeverRepairedPrematurely() {
        state.snapshot();

        session.popupActive = true;
        platformNullsHandlers();

        for (int i = 0; i < 1000; i++) {
            state.repair();
            state.snapshot();
        }

        Assertions.assertNull(commands.getHandler(LINE_START),
            "a popup left open must not be treated as a finished session");
    }

    /** Handlers belong to the editor they were captured from. */
    @Test
    public void activatingAnotherEditorNeverInstallsTheFirstEditorsHandler() {
        state.snapshot(); // caches editor A's handlers

        commands.activePart = EDITOR_B;
        platformNullsHandlers();
        state.repair();

        Assertions.assertNull(commands.getHandler(LINE_START),
            "editor A's handler must not be installed while editor B is active");
    }

    @Test
    public void eachEditorIsRepairedWithItsOwnHandler() {
        state.snapshot(); // editor A

        commands.activePart = EDITOR_B;
        IHandler lineStartForB = new FakeHandler();
        commands.setHandler(LINE_START, lineStartForB);
        state.snapshot(); // editor B

        platformNullsHandlers();
        state.repair();
        Assertions.assertSame(lineStartForB, commands.getHandler(LINE_START), "editor B's own handler");

        platformNullsHandlers();
        commands.activePart = EDITOR_A;
        state.repair();
        Assertions.assertSame(lineStartHandler, commands.getHandler(LINE_START), "editor A's own handler");
    }

    /** Closing an editor must release its cached handlers. */
    @Test
    public void forgettingAPartReleasesItsCachedHandlers() {
        state.snapshot();

        state.forgetPart(EDITOR_A);
        platformNullsHandlers();
        state.repair();

        Assertions.assertNull(commands.getHandler(LINE_START), "cache for a closed editor must be gone");
    }

    /** Everything the guard claims to guard must actually be restored. */
    @Test
    public void everyGuardedCommandIsRestored() {
        List<IHandler> healthy = new ArrayList<>();
        for (String commandId : AssistCommandHandlerGuard.GUARDED_COMMAND_IDS) {
            IHandler handler = new FakeHandler();
            healthy.add(handler);
            commands.setHandler(commandId, handler);
        }
        state.snapshot();

        platformNullsHandlers();
        state.repair();

        for (int i = 0; i < AssistCommandHandlerGuard.GUARDED_COMMAND_IDS.length; i++) {
            String commandId = AssistCommandHandlerGuard.GUARDED_COMMAND_IDS[i];
            Assertions.assertSame(healthy.get(i), commands.getHandler(commandId),
                "not restored: " + commandId);
        }
    }

    /** The guarded list must not claim commands the platform only replaces (LINE_UP/LINE_DOWN). */
    @Test
    public void guardedListOnlyCoversNulledCommands() {
        List<String> guarded = List.of(AssistCommandHandlerGuard.GUARDED_COMMAND_IDS);

        Assertions.assertFalse(guarded.contains(ITextEditorActionDefinitionIds.LINE_UP),
            "LINE_UP is replaced, not nulled, so the guard cannot restore it");
        Assertions.assertFalse(guarded.contains(ITextEditorActionDefinitionIds.LINE_DOWN),
            "LINE_DOWN is replaced, not nulled, so the guard cannot restore it");
        Assertions.assertTrue(guarded.contains(LINE_START));
        Assertions.assertTrue(guarded.contains(LINE_END));
    }

    /** A second editor's open popup must block repair, since the handlers are global. */
    @Test
    public void anotherEditorsOpenPopupBlocksRepair() {
        state.snapshot();

        FakePopup otherEditorSession = new FakePopup();
        state.registerPopupOwner(otherEditorSession);
        otherEditorSession.popupActive = true;

        platformNullsHandlers();
        state.repair();

        Assertions.assertNull(commands.getHandler(LINE_START), "another assistant is mid-session");
    }

    /** A broken assistant must not be able to claim a session is running forever. */
    @Test
    public void aThrowingPopupOwnerDoesNotBlockRepair() {
        state.snapshot();

        session.broken = true;
        platformNullsHandlers();
        state.repair();

        Assertions.assertSame(lineStartHandler, commands.getHandler(LINE_START),
            "an assistant that fails to report state must not disable the guard");
    }

    @Test
    public void disposedGuardNoLongerBlocksRepair() {
        state.snapshot();

        session.popupActive = true;
        platformNullsHandlers();
        state.unregisterPopupOwner(session);

        state.repair();

        Assertions.assertSame(lineStartHandler, commands.getHandler(LINE_START));
    }

    @Test
    public void foreignHandledHandlerIsNotOverwritten() {
        state.snapshot();

        IHandler foreign = new FakeHandler(true);
        commands.setHandler(LINE_START, foreign);

        state.repair();

        Assertions.assertSame(foreign, commands.getHandler(LINE_START), "a live foreign handler must not be replaced");
    }

    @Test
    public void unhandledButPresentHandlerIsNotOverwritten() {
        state.snapshot();

        IHandler disabledForeign = new FakeHandler(false);
        commands.setHandler(LINE_START, disabledForeign);

        state.repair();

        Assertions.assertSame(disabledForeign, commands.getHandler(LINE_START),
            "only handler == null is the #9414 poisoned state; a present but unhandled handler must be kept");
    }

    @Test
    public void snapshotPrefersLatestHealthyHandler() {
        state.snapshot();

        IHandler replacement = new FakeHandler();
        commands.setHandler(LINE_START, replacement);
        state.snapshot();

        platformNullsHandlers();
        state.repair();

        Assertions.assertSame(replacement, commands.getHandler(LINE_START), "the latest healthy handler must win");
    }

    @Test
    public void snapshotIgnoresUnhandledCommands() {
        commands.setHandler(LINE_START, new FakeHandler(false));
        state.snapshot();

        commands.setHandler(LINE_START, null);
        commands.setHandler(LINE_END, null);
        state.repair();

        Assertions.assertNull(commands.getHandler(LINE_START), "an unhandled handler must not be cached");
        Assertions.assertSame(lineEndHandler, commands.getHandler(LINE_END));
    }

    @Test
    public void repairWithoutSnapshotDoesNothing() {
        platformNullsHandlers();

        state.repair();

        Assertions.assertNull(commands.getHandler(LINE_START));
        Assertions.assertNull(commands.getHandler(LINE_END));
    }

    @Test
    public void noSnapshotWhileAPopupIsOpen() {
        session.popupActive = true;
        state.snapshot();
        session.popupActive = false;

        platformNullsHandlers();
        state.repair();

        Assertions.assertNull(commands.getHandler(LINE_START),
            "a snapshot taken mid-session could cache the platform's replacement handlers");
    }

    @Test
    public void unavailableCommandServiceIsNoop() {
        state.snapshot();
        commands.available = false;

        platformNullsHandlers();
        Assertions.assertDoesNotThrow(() -> {
            state.snapshot();
            state.repair();
        });
        Assertions.assertNull(commands.getHandler(LINE_START));
    }

    @Test
    public void missingActiveEditorIsNoop() {
        state.snapshot();
        commands.activePart = null;

        platformNullsHandlers();
        Assertions.assertDoesNotThrow(() -> {
            state.snapshot();
            state.repair();
        });
        Assertions.assertNull(commands.getHandler(LINE_START));
    }
}
