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

/**
 * Live proposal popup state of one content assistant, as used by
 * {@link AssistCommandHandlerGuard} to tell whether an assist session is really in progress
 * (see #9414). Deliberately derived from the UI on every call rather than from event
 * bookkeeping, which a lost event could leave latched.
 */
interface AssistPopupOwner {
    boolean isOwnPopupActive();
}
