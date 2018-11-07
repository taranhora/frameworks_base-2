/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.intelligence;

import android.Manifest;
import android.annotation.NonNull;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.intelligence.ContentCaptureEvent;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.IResultReceiver;
import com.android.server.AbstractPerUserSystemService;

import java.io.PrintWriter;
import java.util.List;

/**
 * Per-user instance of {@link IntelligenceManagerService}.
 */
final class IntelligencePerUserService
        extends AbstractPerUserSystemService<IntelligencePerUserService> {

    private static final String TAG = "IntelligencePerUserService";

    private static int sNextSessionId;

    // TODO(b/111276913): should key by componentName + taskId or ActivityToken
    @GuardedBy("mLock")
    private final ArrayMap<ComponentName, ContentCaptureSession> mSessions = new ArrayMap<>();

    // TODO(b/111276913): add mechanism to prune stale sessions, similar to Autofill's

    protected IntelligencePerUserService(
            IntelligenceManagerService master, Object lock, int userId) {
        super(master, lock, userId);
    }

    @Override // from PerUserSystemService
    protected ServiceInfo newServiceInfo(@NonNull ComponentName serviceComponent)
            throws NameNotFoundException {

        ServiceInfo si;
        try {
            // TODO(b/111276913): must check that either the service is from a system component,
            // or it matches a service set by shell cmd (so it can be used on CTS tests and when
            // OEMs are implementing the real service
            si = AppGlobals.getPackageManager().getServiceInfo(serviceComponent,
                    PackageManager.GET_META_DATA, mUserId);
        } catch (RemoteException e) {
            Slog.w(TAG, "Could not get service for " + serviceComponent + ": " + e);
            return null;
        }
        if (!Manifest.permission.BIND_INTELLIGENCE_SERVICE.equals(si.permission)) {
            Slog.w(TAG, "IntelligenceService from '" + si.packageName
                    + "' does not require permission "
                    + Manifest.permission.BIND_INTELLIGENCE_SERVICE);
            throw new SecurityException("Service does not require permission "
                    + Manifest.permission.BIND_INTELLIGENCE_SERVICE);
        }
        return si;
    }

    // TODO(b/111276913): log metrics
    @GuardedBy("mLock")
    public void startSessionLocked(@NonNull IBinder activityToken,
            @NonNull ComponentName componentName, int taskId, int displayId, int localSessionId,
            int flags, @NonNull IResultReceiver resultReceiver) {
        final ComponentName serviceComponentName = getServiceComponentName();
        if (serviceComponentName == null) {
            // TODO(b/111276913): this happens when the system service is starting, we should
            // probably handle it in a more elegant way (like waiting for boot_complete or
            // something like that
            Slog.w(TAG, "startSession(" + activityToken + "): hold your horses");
            return;
        }

        ContentCaptureSession session = mSessions.get(componentName);
        if (session != null) {
            if (mMaster.debug) {
                Slog.d(TAG, "startSession(): reusing session " + session.getGlobalSessionId()
                        + " for " + componentName);
            }
            // TODO(b/111276913): check if local ids match and decide what to do if they don't
            // TODO(b/111276913): should we call session.notifySessionStartedLocked() again??
            // if not, move notifySessionStartedLocked() into session constructor
            sendToClient(resultReceiver, session.getGlobalSessionId());
            return;
        }

        // TODO(b/117779333): get from mMaster once it's moved to superclass
        final boolean bindInstantServiceAllowed = false;

        session = new ContentCaptureSession(getContext(), mUserId, mLock, activityToken,
                this, serviceComponentName, componentName, taskId, displayId, localSessionId,
                ++sNextSessionId, flags, bindInstantServiceAllowed, mMaster.verbose);
        if (mMaster.verbose) {
            Slog.v(TAG, "startSession(): new session for " + componentName + "; globalId ="
                    + session.getGlobalSessionId());
        }
        mSessions.put(componentName, session);
        session.notifySessionStartedLocked();
        sendToClient(resultReceiver, session.getGlobalSessionId());
    }

    // TODO(b/111276913): log metrics
    @GuardedBy("mLock")
    public void finishSessionLocked(@NonNull IBinder activityToken,
            @NonNull ComponentName componentName, int localSessionId, int globalSessionId) {
        final ContentCaptureSession session = mSessions.get(componentName);
        if (session == null) {
            Slog.w(TAG, "finishSession(): no session for " + componentName);
            return;
        }
        if (mMaster.verbose) {
            Slog.v(TAG, "finishSession(): comp=" + componentName + "; globalId ="
                    + session.getGlobalSessionId());
        }
        // TODO(b/111276913): check if all arguments match existing session and throw exception if
        // not. Or just use componentName if we change AIDL to pass just ApplicationToken and
        // retrieve componentName from AMInternal
        session.removeSelfLocked(true);
    }

    @GuardedBy("mLock")
    public void sendEventsLocked(@NonNull ComponentName componentName,
            @NonNull List<ContentCaptureEvent> events) {
        final ContentCaptureSession session = mSessions.get(componentName);
        if (session == null) {
            Slog.w(TAG, "sendEventsLocked(): no session for " + componentName);
            return;
        }
        if (mMaster.verbose) {
            Slog.v(TAG, "sendEventsLocked(): comp=" + componentName + "; events =" + events.size());
        }
        session.sendEventsLocked(events);
    }

    @GuardedBy("mLock")
    public void removeSessionLocked(@NonNull ComponentName key) {
        mSessions.remove(key);
    }

    @Override
    protected void dumpLocked(String prefix, PrintWriter pw) {
        super.dumpLocked(prefix, pw);
        pw.print(prefix); pw.print("next id: "); pw.println(sNextSessionId);
        if (mSessions.isEmpty()) {
            pw.print(prefix); pw.println("no sessions");
        } else {
            final int size = mSessions.size();
            pw.print(prefix); pw.print("number sessions: "); pw.println(size);
            final String prefix2 = prefix + "  ";
            for (int i = 0; i < size; i++) {
                pw.print(prefix); pw.print("session@"); pw.println(i);
                final ContentCaptureSession session = mSessions.valueAt(i);
                session.dumpLocked(prefix2, pw);
            }
        }
    }

    private static void sendToClient(@NonNull IResultReceiver resultReceiver, int value) {
        try {
            resultReceiver.send(value, null);
        } catch (RemoteException e) {
            Slog.w(TAG, "Error async reporting result to client: " + e);
        }
    }

}
