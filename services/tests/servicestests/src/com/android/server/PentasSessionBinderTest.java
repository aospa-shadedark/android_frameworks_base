/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.android.server;

import static org.junit.Assert.assertEquals;

import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class PentasSessionBinderTest {
    private final TestContext mContext = new TestContext();
    private PentasSessionBinder mSessions;

    @Before
    public void setUp() {
        mSessions = new PentasSessionBinder(mContext, new Handler(Looper.getMainLooper()));
    }

    @Test
    public void replacementBindsBeforeReleasingPreviousSession() {
        mSessions.start("game.one", 0);
        mSessions.start("game.two", 0);
        assertEquals(List.of("bind:game.one:0", "bind:game.two:0", "unbind:game.one:0"),
                mContext.events);
        assertEquals("pentas://session/game.two?mode=1", mContext.bindings.get(1).intent
                .getData().toString());
        assertEquals("org.yasvara.pentas.session.SessionService", mContext.bindings.get(1)
                .intent.getComponent().getClassName());
    }

    @Test
    public void duplicateStartDoesNotRebindButUserChangeDoes() {
        mSessions.start("game.one", 0);
        mSessions.start("game.one", 0);
        assertEquals(1, mContext.bindings.size());
        mSessions.start("game.one", 10);
        assertEquals(List.of("bind:game.one:0", "bind:game.one:10", "unbind:game.one:0"),
                mContext.events);
    }

    @Test
    public void staleDeathCannotReplaceNewSession() {
        mSessions.start("game.one", 0);
        ServiceConnection old = mContext.bindings.get(0).connection;
        mSessions.start("game.two", 0);
        old.onBindingDied(null);
        assertEquals(2, mContext.bindings.size());
    }

    @Test
    public void bindingDeathRetriesOnlyOnce() {
        mSessions.start("game.one", 10);
        mContext.bindings.get(0).connection.onBindingDied(null);
        assertEquals(2, mContext.bindings.size());
        assertEquals(10, mContext.bindings.get(1).userId);
        mContext.bindings.get(1).connection.onBindingDied(null);
        assertEquals(2, mContext.bindings.size());
        mSessions.stop();
        assertEquals(4, mContext.events.size());
    }

    @Test
    public void unavailableServiceDoesNotPreventLaterStart() {
        mContext.available = false;
        mSessions.start("game.one", 0);
        mSessions.stop();
        mContext.available = true;
        mSessions.start("game.two", 0);
        mSessions.stop();
        assertEquals(List.of("bind:game.one:0", "unbind:game.one:0",
                "bind:game.two:0", "unbind:game.two:0"), mContext.events);
    }

    @Test
    public void nullBindingIsReleasedAndStopIsIdempotent() {
        mSessions.start("game.one", 0);
        mContext.bindings.get(0).connection.onNullBinding(null);
        mSessions.stop();
        mSessions.stop();
        assertEquals(List.of("bind:game.one:0", "unbind:game.one:0"), mContext.events);
    }

    private static final class Binding {
        final Intent intent;
        final ServiceConnection connection;
        final int userId;

        Binding(Intent intent, ServiceConnection connection, int userId) {
            this.intent = intent;
            this.connection = connection;
            this.userId = userId;
        }

        String name() {
            return intent.getData().getLastPathSegment() + ":" + userId;
        }
    }

    private static final class TestContext extends ContextWrapper {
        final List<String> events = new ArrayList<>();
        final List<Binding> bindings = new ArrayList<>();
        boolean available = true;

        TestContext() {
            super(null);
        }

        @Override
        public boolean bindServiceAsUser(Intent intent, ServiceConnection connection, int flags,
                Handler handler, UserHandle user) {
            Binding binding = new Binding(intent, connection, user.getIdentifier());
            bindings.add(binding);
            events.add("bind:" + binding.name());
            return available;
        }

        @Override
        public void unbindService(ServiceConnection connection) {
            Binding binding = bindings.stream().filter(b -> b.connection == connection)
                    .findFirst().orElseThrow();
            events.add("unbind:" + binding.name());
        }
    }
}
