package org.archpheneos.manager;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Process-bound references that keep immutable runtime packs alive. */
final class RuntimePackLeaseRegistry {
    private static final Map<IBinder, Lease> LEASES = new HashMap<>();

    private RuntimePackLeaseRegistry() {}

    static void acquire(Context context, String androidPackage, String packId, IBinder token)
            throws RemoteException {
        requireIdentity(androidPackage, packId, token);
        Context appContext = context.getApplicationContext();
        synchronized (RuntimePackStore.class) {
            synchronized (LEASES) {
                Lease existing = LEASES.get(token);
                if (existing != null) {
                    if (existing.androidPackage.equals(androidPackage)
                            && existing.packId.equals(packId)) return;
                    throw new SecurityException("Runtime lease token is already in use");
                }
                IBinder.DeathRecipient death = () -> releaseDead(token);
                token.linkToDeath(death, 0);
                LEASES.put(token, new Lease(
                        appContext, androidPackage, packId, token, death));
            }
        }
        Log.i("ArchpheneRuntime", "Acquired runtime pack lease " + packId
                + " for " + androidPackage);
    }

    static void release(String androidPackage, String packId, IBinder token) {
        requireIdentity(androidPackage, packId, token);
        Lease removed;
        synchronized (RuntimePackStore.class) {
            synchronized (LEASES) {
                removed = LEASES.get(token);
                if (removed == null) return;
                if (!removed.androidPackage.equals(androidPackage)
                        || !removed.packId.equals(packId)) {
                    throw new SecurityException("Runtime lease identity mismatch");
                }
                LEASES.remove(token);
            }
            removed.token.unlinkToDeath(removed.death, 0);
            Log.i("ArchpheneRuntime", "Released runtime pack lease " + packId
                    + " for " + androidPackage);
            collectIfUnbound(removed);
        }
    }

    static Set<String> packIds() {
        synchronized (LEASES) {
            HashSet<String> result = new HashSet<>();
            for (Lease lease : LEASES.values()) result.add(lease.packId);
            return result;
        }
    }

    static boolean isLeased(String packId) {
        synchronized (LEASES) {
            for (Lease lease : LEASES.values()) {
                if (lease.packId.equals(packId)) return true;
            }
            return false;
        }
    }

    static void verifyForTest(Context context) throws Exception {
        String pack = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        android.os.Binder token = new android.os.Binder();
        acquire(context, "org.archphene.test.wrapper", pack, token);
        if (!isLeased(pack) || !packIds().contains(pack)) {
            throw new IllegalStateException("Runtime lease was not registered");
        }
        acquire(context, "org.archphene.test.wrapper", pack, token);
        try {
            release("org.archphene.test.other", pack, token);
            throw new IllegalStateException("Runtime lease accepted the wrong caller");
        } catch (SecurityException expected) {
            if (!isLeased(pack)) {
                throw new IllegalStateException("Rejected release removed the lease");
            }
        }
        release("org.archphene.test.wrapper", pack, token);
        if (isLeased(pack)) throw new IllegalStateException("Runtime lease was not released");
    }

    private static void releaseDead(IBinder token) {
        Lease removed;
        synchronized (RuntimePackStore.class) {
            synchronized (LEASES) {
                removed = LEASES.remove(token);
            }
            if (removed == null) return;
            Log.i("ArchpheneRuntime", "Runtime process died; released pack lease "
                    + removed.packId + " for " + removed.androidPackage);
            collectIfUnbound(removed);
        }
    }

    private static void collectIfUnbound(Lease lease) {
        if (isLeased(lease.packId)) return;
        try {
            RuntimePackStore.deleteIfUnbound(lease.context, lease.packId);
        } catch (Exception error) {
            Log.w("ArchpheneRuntime", "Could not collect released runtime pack", error);
        }
    }

    private static void requireIdentity(String androidPackage, String packId, IBinder token) {
        if (androidPackage == null || !androidPackage.matches("[a-zA-Z0-9._]{3,255}")
                || packId == null || !packId.matches("[a-f0-9]{64}") || token == null) {
            throw new SecurityException("Invalid runtime lease identity");
        }
    }

    private static final class Lease {
        final Context context;
        final String androidPackage;
        final String packId;
        final IBinder token;
        final IBinder.DeathRecipient death;

        Lease(Context context, String androidPackage, String packId, IBinder token,
                IBinder.DeathRecipient death) {
            this.context = context;
            this.androidPackage = androidPackage;
            this.packId = packId;
            this.token = token;
            this.death = death;
        }
    }
}
