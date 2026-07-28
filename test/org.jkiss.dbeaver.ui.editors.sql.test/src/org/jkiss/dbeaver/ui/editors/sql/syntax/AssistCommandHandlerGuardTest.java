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

import java.util.HashMap;
import java.util.Map;

/**
 * Tests for the {@link AssistCommandHandlerGuard.GuardState} snapshot/repair state machine
 * (see #9414). Uses a fake {@link AssistCommandHandlerGuard.CommandAccess} and a fake clock,
 * so no workbench is needed.
 */
public class AssistCommandHandlerGuardTest {

    private static final String LINE_START = ITextEditorActionDefinitionIds.LINE_START;
    private static final String LINE_END = ITextEditorActionDefinitionIds.LINE_END;

    /** Stand-ins for the per-editor assist sessions the guard keys its tracking on. */
    private static final Object SESSION_A = new Object();
    private static final Object SESSION_B = new Object();

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

    private static class FakeCommandAccess implements AssistCommandHandlerGuard.CommandAccess {
        private final Map<String, IHandler> handlers = new HashMap<>();
        private boolean available = true;

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
    }

    private FakeCommandAccess commands;
    private AssistCommandHandlerGuard.GuardState state;
    private IHandler lineStartHandler;
    private IHandler lineEndHandler;
    private long now;

    @BeforeEach
    public void setUp() {
        commands = new FakeCommandAccess();
        now = 1_000_000L;
        state = new AssistCommandHandlerGuard.GuardState(commands, () -> now);
        lineStartHandler = new FakeHandler();
        lineEndHandler = new FakeHandler();
        commands.setHandler(LINE_START, lineStartHandler);
        commands.setHandler(LINE_END, lineEndHandler);
    }

    /** Simulates KeyBindingSupportForAssistant nulling the handlers at session start. */
    private void platformNullsHandlers() {
        commands.setHandler(LINE_START, null);
        commands.setHandler(LINE_END, null);
    }

    @Test
    public void healthySnapshotIsRestoredAfterStarvedRestore() {
        state.snapshot();

        state.sessionStarted(SESSION_A);
        platformNullsHandlers();
        state.sessionEnded(SESSION_A);
        // the platform restore was starved (a listener threw): handlers stay null

        state.repair();

        Assertions.assertSame(lineStartHandler, commands.getHandler(LINE_START));
        Assertions.assertSame(lineEndHandler, commands.getHandler(LINE_END));
    }

    /**
     * Regression: a session whose end event never arrives must not disable the guard forever.
     * This is the same failure the guard exists to repair, so it must not be able to latch it
     * into a permanently "session active" state.
     */
    @Test
    public void lostEndEventExpiresInsteadOfDisablingTheGuard() {
        state.snapshot();

        state.sessionStarted(SESSION_A);
        platformNullsHandlers();
        // no sessionEnded(): the end event was never delivered

        state.repair();
        Assertions.assertNull(commands.getHandler(LINE_START), "the popup may still be open right now");

        now += AssistCommandHandlerGuard.GuardState.SESSION_STALE_MS + 1;
        state.repair();

        Assertions.assertSame(lineStartHandler, commands.getHandler(LINE_START),
            "a stale session must not block repair");
    }

    /** Regression: editor activation proves no popup is open, so leaked sessions are dropped. */
    @Test
    public void editorActivationClearsLeakedSessions() {
        state.snapshot();

        state.sessionStarted(SESSION_A);
        platformNullsHandlers();
        // end event lost again

        state.sessionsFinished();
        state.repair();

        Assertions.assertSame(lineStartHandler, commands.getHandler(LINE_START));
    }

    /** Regression: snapshotting must also recover after a leaked session, not just repair. */
    @Test
    public void snapshotRecoversAfterLeakedSession() {
        state.sessionStarted(SESSION_A);
        // leak, before anything was ever cached
        state.sessionsFinished();

        state.snapshot();
        platformNullsHandlers();
        state.repair();

        Assertions.assertSame(lineStartHandler, commands.getHandler(LINE_START),
            "a leaked session must not prevent the first snapshot");
    }

    @Test
    public void noRepairWhileSessionIsActive() {
        state.snapshot();

        state.sessionStarted(SESSION_A);
        platformNullsHandlers();

        state.repair();
        Assertions.assertNull(commands.getHandler(LINE_START), "must not repair during an active session");

        state.sessionEnded(SESSION_A);
        state.repair();
        Assertions.assertSame(lineStartHandler, commands.getHandler(LINE_START));
    }

    @Test
    public void noRepairWhileOverlappingSessionIsStillActive() {
        state.snapshot();

        state.sessionStarted(SESSION_A);
        state.sessionStarted(SESSION_B);
        platformNullsHandlers();
        state.sessionEnded(SESSION_A);

        state.repair();
        Assertions.assertNull(commands.getHandler(LINE_START), "a second editor's session is still active");

        state.sessionEnded(SESSION_B);
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

        state.sessionStarted(SESSION_A);
        platformNullsHandlers();
        state.sessionEnded(SESSION_A);
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
    public void noSnapshotDuringActiveSession() {
        state.sessionStarted(SESSION_A);
        state.snapshot();
        state.sessionEnded(SESSION_A);

        platformNullsHandlers();
        state.repair();

        Assertions.assertNull(commands.getHandler(LINE_START),
            "a snapshot taken during a session could cache the platform's replacement handlers");
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
    public void unbalancedSessionEndIsHarmless() {
        state.sessionEnded(SESSION_A);
        Assertions.assertFalse(state.isSessionActive());

        state.sessionStarted(SESSION_A);
        Assertions.assertTrue(state.isSessionActive());
        state.sessionEnded(SESSION_A);
        Assertions.assertFalse(state.isSessionActive());
    }
}
