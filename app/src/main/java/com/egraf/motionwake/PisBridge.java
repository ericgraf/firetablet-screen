/*
 * PisBridge — reflective access to the IPackageInstaller binder.
 *
 * On these Fire builds the context service named "package_installer" is
 * NOT registered in the service manager (checked: 230 services, none
 * match), so `Context.getSystemService("package_installer")` returns null
 * even though installs clearly work. The binder IS reachable the same way
 * the framework's own PackageInstaller constructor gets it:
 *
 *     ActivityThread.sPackageManager.getPackageInstaller()
 *
 * (verified live via /update/diag: the sPackageManager static field
 * exists and its IPackageManager surface exposes getPackageInstaller()).
 *
 * Everything here is reflection because the exact AIDL method set of
 * IPackageInstaller / .Session can differ between the compile jar and
 * the SDK-30 device runtime (renamed aliases, openWrite returning either
 * OutputStream or ParcelFileDescriptor, etc.).
 */
package com.egraf.motionwake;

import android.util.Log;

import java.lang.reflect.Method;

final class PisBridge {

    private static final String TAG = "PisBridge";

    private PisBridge() {
    }

    /** The IPackageInstaller binder, or null when unavailable. */
    static Object installer() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object sPM = null;
            for (java.lang.reflect.Field f : at.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (!"android.content.pm.IPackageManager".equals(f.getType().getName())) {
                    continue;
                }
                f.setAccessible(true);
                sPM = f.get(null);
                break;
            }
            if (sPM == null) {
                Log.w(TAG, "no IPackageManager static field on ActivityThread");
                return null;
            }
            for (Method m : sPM.getClass().getInterfaces()[0].getMethods()) {
                if (!"getPackageInstaller".equals(m.getName())
                        || m.getParameterTypes().length != 0) {
                    continue;
                }
                Object pis = m.invoke(sPM);
                if (pis != null) {
                    Log.i(TAG, "IPackageInstaller reachable via getPackageInstaller()");
                }
                return pis;
            }
            Log.w(TAG, "IPackageManager surface has no getPackageInstaller()");
            return null;
        } catch (Throwable e) {
            Log.w(TAG, "installer() failed: " + e);
            return null;
        }
    }

    /** createSession(SessionParams) → session id (-1 when the method is absent). */
    static int createSession(Object pis, Object params) {
        java.util.List<String> saw = new java.util.ArrayList<String>();
        try {
            for (Method m : pis.getClass().getMethods()) {
                String n = m.getName();
                if (n.indexOf("create") < 0 && n.indexOf("session") < 0
                        && n.indexOf("Session") < 0) {
                    continue;
                }
                StringBuilder sig = new StringBuilder();
                for (Class<?> c : m.getParameterTypes()) {
                    sig.append(c.getName()).append(";");
                }
                saw.add(n + "(" + sig + ")");
                if (!"createSession".equals(n) || m.getParameterTypes().length != 1) {
                    continue;
                }
                try {
                    Object r = m.invoke(pis, params);
                    return r instanceof Integer ? ((Integer) r).intValue() : -1;
                } catch (Throwable inv) {
                    Log.w(TAG, "createSession invoke failed", inv);
                    return -1;
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "createSession failed", e);
        }
        Log.w(TAG, "no createSession found. candidates=" + saw);
        return -1;
    }

    /** openSession(int) → the Session binder, or null. */
    static Object openSession(Object pis, int sessionId) {
        try {
            for (Method m : pis.getClass().getMethods()) {
                if (!"openSession".equals(m.getName())
                        || m.getParameterTypes().length != 1) {
                    continue;
                }
                if (m.getParameterTypes()[0] != int.class) {
                    continue;
                }
                return m.invoke(pis, sessionId);
            }
        } catch (Throwable e) {
            Log.w(TAG, "openSession failed", e);
        }
        return null;
    }

    /** openWrite(String) — runtime may return OutputStream or ParcelFileDescriptor. */
    static Object sessionOpenWrite(Object session, String filePath) {
        try {
            for (Method m : session.getClass().getMethods()) {
                if (!"openWrite".equals(m.getName())
                        || m.getParameterTypes().length != 1
                        || m.getParameterTypes()[0] != String.class) {
                    continue;
                }
                return m.invoke(session, filePath);
            }
        } catch (Throwable e) {
            Log.w(TAG, "openWrite failed", e);
        }
        return null;
    }

    static void sessionSeal(Object session) {
        invoke0(session, "seal");
    }

    static void sessionAbandon(Object session) {
        invoke0(session, "abandon");
    }

    /** commit(IntentSender) — the AIDL parameter type is android.content.IntentSender. */
    static void sessionCommit(Object session, Object intentSender) {
        try {
            for (Method m : session.getClass().getMethods()) {
                if (!"commit".equals(m.getName()) || m.getParameterTypes().length != 1) {
                    continue;
                }
                m.invoke(session, intentSender);
                return;
            }
            throw new NoSuchMethodException("commit(IntentSender)");
        } catch (Throwable e) {
            throw new IllegalStateException("commit failed: " + e, e);
        }
    }

    /** Write bytes through openWrite's result, handling both return types. */
    static void writeThrough(Object session, String filePath, byte[] data) throws Exception {
        Object w = sessionOpenWrite(session, filePath);
        if (w == null) {
            throw new IllegalStateException("openWrite('" + filePath + "') missing");
        }
        if (w instanceof java.io.OutputStream) {
            java.io.OutputStream out = (java.io.OutputStream) w;
            try {
                out.write(data);
                out.flush();
            } finally {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
            return;
        }
        // ParcelFileDescriptor (or runtime alias) → grab the raw fd.
        for (Method gfd : w.getClass().getMethods()) {
            if ("getFileDescriptor".equals(gfd.getName())
                    && gfd.getParameterTypes().length == 0) {
                java.io.FileDescriptor fd =
                        (java.io.FileDescriptor) gfd.invoke(w);
                java.io.OutputStream out = new java.io.FileOutputStream(fd);
                try {
                    out.write(data);
                    out.flush();
                } finally {
                    try {
                        out.close();
                    } catch (Exception ignored) {
                    }
                }
                return;
            }
        }
        throw new IllegalStateException(
                "openWrite returned unknown type " + w.getClass().getName());
    }

    private static void invoke0(Object target, String name) {
        try {
            for (Method m : target.getClass().getMethods()) {
                if (name.equals(m.getName()) && m.getParameterTypes().length == 0) {
                    m.invoke(target);
                    return;
                }
            }
        } catch (Throwable e) {
            throw new IllegalStateException(name + " failed: " + e, e);
        }
    }
}
