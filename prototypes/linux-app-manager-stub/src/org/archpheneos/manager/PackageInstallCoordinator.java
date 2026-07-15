package org.archpheneos.manager;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs independent package preparation jobs with serialized wrapper mutation/signing. */
final class PackageInstallCoordinator {
    interface Listener {
        void onChanged(PackageInstallJobStore.Snapshot state, boolean terminal);
    }

    private static final ExecutorService PREPARATION = Executors.newFixedThreadPool(2);
    private static final Semaphore WRAPPER_MUTATION = new Semaphore(1, true);
    private static final Semaphore ANDROID_INSTALL = new Semaphore(1, true);
    private static final ConcurrentHashMap<String, ApkUpdateInstaller.Operation> OPERATIONS =
            new ConcurrentHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PackageInstallCoordinator() {}

    static boolean start(Activity activity, ArchPackageRepository.PackageResult source,
            Listener listener) {
        String id = PackageInstallJobStore.key(source);
        ApkUpdateInstaller.Operation preparation = new ApkUpdateInstaller.Operation();
        if (OPERATIONS.putIfAbsent(id, preparation) != null) return false;
        notify(activity, listener, PackageInstallJobStore.begin(activity, id), false);
        PREPARATION.execute(() -> prepare(activity, source, id, preparation, listener));
        return true;
    }

    static ApkUpdateInstaller.Operation operation(String id) {
        return OPERATIONS.get(id);
    }

    static boolean hasActiveOperations() {
        return !OPERATIONS.isEmpty();
    }

    static void cancel(String id) {
        ApkUpdateInstaller.Operation operation = OPERATIONS.get(id);
        if (operation != null) operation.cancel();
    }

    private static void prepare(Activity activity, ArchPackageRepository.PackageResult source,
            String id, ApkUpdateInstaller.Operation preparation, Listener listener) {
        Thread worker = Thread.currentThread();
        preparation.setCancellationHook(worker::interrupt);
        ArchPackageRuntime.StagedTransaction staged = null;
        boolean handedOff = false;
        try {
            if (!ArchRuntimePolicy.supports(source.architecture)) {
                throw new UnsupportedOperationException("Package architecture does not match device runtime");
            }
            checkInterrupted();
            update(activity, listener, id, PackageInstallJobStore.RUNNING,
                    ApkUpdateInstaller.Phase.DOWNLOAD, 3,
                    "Resolving signed Arch transaction", "", false);
            staged = ArchPackageRuntime.stageTransaction(
                    activity.getApplicationContext(), source.name, source.executable,
                    (percent, status) -> update(activity, listener, id,
                            PackageInstallJobStore.RUNNING,
                            ApkUpdateInstaller.Phase.DOWNLOAD, percent, status, "", false));
            checkInterrupted();
            if (staged.classification.kind == ArchPackageClassifier.Kind.TERMINAL) {
                update(activity, listener, id, PackageInstallJobStore.RUNNING,
                        ApkUpdateInstaller.Phase.INSTALL, 90,
                        "Publishing Terminal environment package", "", false);
                ManagedPackageStore.install(activity, source, staged);
                TrackedPackageStore.remove(activity, source.repository,
                        source.name, source.architecture);
                ArchPackageRuntime.releaseStaging(activity, staged);
                staged = null;
                OPERATIONS.remove(id, preparation);
                update(activity, listener, id, PackageInstallJobStore.COMPLETE,
                        ApkUpdateInstaller.Phase.COMPLETE, 100,
                        "Installed in Archphene Terminal", "", true);
                return;
            }
            if (staged.classification.kind != ArchPackageClassifier.Kind.DESKTOP) {
                ArchPackageRuntime.releaseStaging(activity, staged);
                staged = null;
                throw new UnsupportedOperationException(source.name
                        + " does not provide a runnable command or desktop entry");
            }

            update(activity, listener, id, PackageInstallJobStore.RUNNING,
                    ApkUpdateInstaller.Phase.INSTALL, 5,
                    "Waiting to build and sign wrapper", "", false);
            WRAPPER_MUTATION.acquire();
            ArchWrapperAssembler.Result result;
            try {
                checkInterrupted();
                update(activity, listener, id, PackageInstallJobStore.RUNNING,
                        ApkUpdateInstaller.Phase.INSTALL, 15,
                        "Building and signing Android wrapper", "", false);
                String resolvedVersion = staged.sourceVersion();
                result = ArchWrapperAssembler.assembleDesktopFromRuntimePack(
                        activity, source.repository, source.name, resolvedVersion,
                        source.architecture, staged.toolkit, staged.classification, staged.root);
            } finally {
                WRAPPER_MUTATION.release();
            }
            checkInterrupted();
            PackageInstallJobStore.setArtifacts(activity, id,
                    result.packageName, staged.runtimePackId);
            update(activity, listener, id, PackageInstallJobStore.WAITING_INSTALL,
                    ApkUpdateInstaller.Phase.INSTALL, 18,
                    "Waiting for Android install slot", "", false);
            ANDROID_INSTALL.acquire();
            AtomicBoolean installSlot = new AtomicBoolean(true);
            try {
                checkInterrupted();
                ArchPackageRuntime.StagedTransaction prepared = staged;
                if (!MAIN.post(() -> beginAndroidInstall(activity, source, id, preparation,
                        prepared, result, listener, installSlot))) {
                    throw new IllegalStateException("Could not schedule Android installer handoff");
                }
                handedOff = true;
            } catch (Exception error) {
                releaseInstallSlot(installSlot);
                throw error;
            }
        } catch (InterruptedException cancelled) {
            Thread.interrupted();
            OPERATIONS.remove(id, preparation);
            update(activity, listener, id, PackageInstallJobStore.CANCELLED,
                    ApkUpdateInstaller.Phase.CANCELLED, 0,
                    "Package install cancelled", "", true);
        } catch (Exception error) {
            OPERATIONS.remove(id, preparation);
            android.util.Log.e("ArchpheneManager", "Package preparation failed for " + id, error);
            update(activity, listener, id, PackageInstallJobStore.ERROR,
                    ApkUpdateInstaller.Phase.ERROR, 0,
                    "Package preparation failed", message(error), true);
        } finally {
            if (!handedOff && staged != null) {
                ArchPackageRuntime.releaseStaging(activity, staged);
            }
        }
    }

    private static void beginAndroidInstall(Activity activity,
            ArchPackageRepository.PackageResult source, String id,
            ApkUpdateInstaller.Operation preparation,
            ArchPackageRuntime.StagedTransaction staged,
            ArchWrapperAssembler.Result result, Listener listener,
            AtomicBoolean installSlot) {
        if (OPERATIONS.get(id) != preparation || !preparation.canCancel()) {
            ArchPackageRuntime.releaseStaging(activity, staged);
            releaseInstallSlot(installSlot);
            update(activity, listener, id, PackageInstallJobStore.CANCELLED,
                    ApkUpdateInstaller.Phase.CANCELLED, 0,
                    "Package install cancelled", "", true);
            return;
        }
        try {
            update(activity, listener, id, PackageInstallJobStore.WAITING_INSTALL,
                    ApkUpdateInstaller.Phase.INSTALL, 20,
                    "Verifying generated APK", "", false);
            final ApkUpdateInstaller.Operation[] installed = new ApkUpdateInstaller.Operation[1];
            ApkUpdateInstaller.Operation operation = ApkUpdateInstaller.installWithProgress(
                    activity, result.apk.toURI().toString(), result.apkSha256,
                    result.packageName, result.signerSha256,
                    (phase, percent, status, terminal) -> {
                        if (!terminal) {
                            update(activity, listener, id,
                                    PackageInstallJobStore.WAITING_INSTALL,
                                    phase, percent, status, "", false);
                            return;
                        }
                        ApkUpdateInstaller.Phase finalPhase = phase;
                        String finalStatus = status;
                        String finalError = "";
                        if (phase == ApkUpdateInstaller.Phase.COMPLETE) {
                            try {
                                RuntimePackStore.activate(activity, result.packageName,
                                        staged.runtimePackId);
                                RuntimePackStore.grantActive(activity, result.packageName);
                                TrackedPackageStore.remove(activity, source.repository,
                                        source.name, source.architecture);
                            } catch (Exception activationError) {
                                finalPhase = ApkUpdateInstaller.Phase.ERROR;
                                finalStatus = "Installed APK but runtime activation failed";
                                finalError = message(activationError);
                            }
                        } else if (phase == ApkUpdateInstaller.Phase.ERROR) {
                            finalError = status;
                        }
                        ArchPackageRuntime.releaseStaging(activity, staged);
                        releaseInstallSlot(installSlot);
                        ApkUpdateInstaller.Operation current = installed[0];
                        if (current != null) OPERATIONS.remove(id, current);
                        String state = finalPhase == ApkUpdateInstaller.Phase.COMPLETE
                                ? PackageInstallJobStore.COMPLETE
                                : finalPhase == ApkUpdateInstaller.Phase.CANCELLED
                                ? PackageInstallJobStore.CANCELLED
                                : PackageInstallJobStore.ERROR;
                        update(activity, listener, id, state, finalPhase,
                                finalPhase == ApkUpdateInstaller.Phase.COMPLETE ? 100 : 0,
                                finalStatus, finalError, true);
                    });
            installed[0] = operation;
            if (!OPERATIONS.replace(id, preparation, operation)) {
                operation.cancel();
                releaseInstallSlot(installSlot);
                update(activity, listener, id, PackageInstallJobStore.CANCELLED,
                        ApkUpdateInstaller.Phase.CANCELLED, 0,
                        "Package install cancelled", "", true);
            }
        } catch (Exception error) {
            OPERATIONS.remove(id, preparation);
            ArchPackageRuntime.releaseStaging(activity, staged);
            releaseInstallSlot(installSlot);
            android.util.Log.e("ArchpheneManager",
                    "Android installer handoff failed for " + id, error);
            update(activity, listener, id, PackageInstallJobStore.ERROR,
                    ApkUpdateInstaller.Phase.ERROR, 0,
                    "Could not start Android installer", message(error), true);
        }
    }

    private static void releaseInstallSlot(AtomicBoolean slot) {
        if (slot.compareAndSet(true, false)) ANDROID_INSTALL.release();
    }

    private static void update(Activity activity, Listener listener, String id, String state,
            ApkUpdateInstaller.Phase phase, int percent, String status, String error,
            boolean terminal) {
        PackageInstallJobStore.Snapshot snapshot = PackageInstallJobStore.update(
                activity, id, state, phase, percent, status, error);
        notify(activity, listener, snapshot, terminal);
    }

    private static void notify(Activity activity, Listener listener,
            PackageInstallJobStore.Snapshot snapshot, boolean terminal) {
        MAIN.post(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                listener.onChanged(snapshot, terminal);
            }
        });
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isEmpty() ? error.getClass().getSimpleName() : value;
    }

    static void verifySchedulingForTest() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Semaphore mutation = new Semaphore(1, true);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        AtomicInteger preparing = new AtomicInteger();
        AtomicInteger maxPreparing = new AtomicInteger();
        AtomicInteger mutating = new AtomicInteger();
        AtomicInteger maxMutating = new AtomicInteger();
        AtomicInteger isolatedFailures = new AtomicInteger();
        for (int index = 0; index < 2; index++) {
            final int task = index;
            executor.execute(() -> {
                ready.countDown();
                try {
                    start.await();
                    int active = preparing.incrementAndGet();
                    maxPreparing.accumulateAndGet(active, Math::max);
                    Thread.sleep(30);
                    mutation.acquire();
                    try {
                        int writers = mutating.incrementAndGet();
                        maxMutating.accumulateAndGet(writers, Math::max);
                        if (task == 0) throw new IllegalStateException("isolated");
                    } catch (IllegalStateException expected) {
                        isolatedFailures.incrementAndGet();
                    } finally {
                        mutating.decrementAndGet();
                        mutation.release();
                    }
                } catch (Exception error) {
                    if (!(error instanceof InterruptedException)) {
                        isolatedFailures.incrementAndGet();
                    }
                } finally {
                    preparing.decrementAndGet();
                    finished.countDown();
                }
            });
        }
        if (!ready.await(2, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Jobs did not queue");
        }
        start.countDown();
        if (!finished.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Package scheduler test timed out");
        }
        executor.shutdownNow();
        if (maxPreparing.get() != 2 || maxMutating.get() != 1
                || isolatedFailures.get() != 1) {
            throw new IllegalStateException("Package scheduler bounds were not enforced");
        }
    }
}
