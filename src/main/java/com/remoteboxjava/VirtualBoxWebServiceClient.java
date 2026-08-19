package com.remoteboxjava;

import com.remoteboxjava.VBoxManageClient.VBoxException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Minimal client for the VirtualBox web service used by RemoteBox 3.7.
 * It reads guests from the authenticated server session rather than the
 * local Windows VirtualBox registry.
 */
public final class VirtualBoxWebServiceClient implements VirtualBoxClient {
    private static final Logger LOG = LogManager.getLogger(VirtualBoxWebServiceClient.class);
    private static final String VBOX_NAMESPACE = "http://www.virtualbox.org/";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int GUEST_LOG_CHUNK_BYTES = 256 * 1024;
    private static final int MAX_GUEST_LOG_CHARACTERS = 8 * 1024 * 1024;
    private static final int SECURITY_PROMPT_TIMEOUT_SECONDS = 10;
    /** The zoom entry only exists once mstsc has connected, which needs the guest to answer. */
    private static final int ZOOM_TIMEOUT_SECONDS = 60;
    /**
     * How many SOAP requests the guest-list load may have in flight. Each guest
     * costs several round trips, so a serial load is dominated by network latency;
     * vboxwebsrv serves requests from a thread pool and handles this comfortably.
     */
    private static final int LIST_PARALLELISM = 8;
    /** How often the still incomplete guest list is handed to the UI. */
    private static final long PARTIAL_PUBLISH_NANOS = Duration.ofMillis(250).toNanos();

    private final URI endpoint;
    private final HttpClient httpClient;
    private final String username;
    private final char[] password;
    private final ExecutorService listExecutor = Executors.newFixedThreadPool(LIST_PARALLELISM, runnable -> {
        Thread worker = new Thread(runnable, "RemoteBox-soap");
        worker.setDaemon(true);
        return worker;
    });
    /**
     * SOAP object references returned by IVirtualBox_getMachines. These values
     * are opaque, session-scoped managed-object references and must be retained
     * rather than reconstructed from a VM UUID.
     * <p>
     * The Swing UI refreshes guests and snapshot previews on separate workers, so
     * this map is shared between threads.
     */
    private final Map<String, String> machineReferences = new ConcurrentHashMap<>();
    /**
     * Guest fields that only change when the machine is reconfigured, keyed by VM
     * UUID. Caching them keeps the periodic refresh down to four round trips per
     * guest instead of eleven.
     */
    private final Map<String, MachineDetails> machineDetails = new ConcurrentHashMap<>();
    /**
     * Older VirtualBox web-service versions predate IMachine drag-and-drop
     * operations. A null value means not yet detected.
     */
    private volatile Boolean dragAndDropSupported;
    private volatile String virtualBoxReference;

    public VirtualBoxWebServiceClient(String endpoint, String username, char[] password) throws VBoxException {
        this.endpoint = normalizeEndpoint(endpoint);
        this.username = username == null ? "" : username;
        this.password = password == null ? new char[0] : password.clone();
        this.httpClient = HttpClient.newBuilder()
                // vboxwebsrv speaks HTTP/1.1 only; asking for HTTP/2 just adds an
                // ALPN negotiation to every new TLS connection.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
        long started = System.nanoTime();
        try {
            this.virtualBoxReference = logon();
            LOG.info("Signed in to {} in {} ms.", this.endpoint, (System.nanoTime() - started) / 1_000_000L);
            if (virtualBoxReference.isBlank()) {
                throw new VBoxException("The VirtualBox web service did not return a session reference.");
            }
        } catch (VBoxException | RuntimeException failure) {
            Arrays.fill(this.password, '\0');
            throw failure;
        }
    }

    private String logon() throws VBoxException {
        return callSingle("IWebsessionManager_logon",
                element("username", username) + element("password", new String(password)));
    }

    /**
     * vboxwebsrv forgets every managed object when it restarts or when the session
     * times out, and then rejects the session reference itself. Signing in again
     * turns that into a hiccup instead of a client that keeps failing until the
     * user reconnects by hand.
     */
    private <T> T withSessionRecovery(SoapTask<Void, T> call) throws VBoxException {
        try {
            return call.run(null);
        } catch (VBoxException exception) {
            if (!isExpiredSession(exception)) {
                throw exception;
            }
            LOG.info("The VirtualBox web-service session expired; signing in again.");
            machineReferences.clear();
            machineDetails.clear();
            virtualBoxReference = logon();
            if (virtualBoxReference.isBlank()) {
                throw new VBoxException("The VirtualBox web service did not return a session reference.");
            }
            return call.run(null);
        }
    }

    private static boolean isExpiredSession(VBoxException exception) {
        String message = exception.getMessage();
        return message != null && message.contains("Invalid managed object reference");
    }

    @Override
    public synchronized String version() throws VBoxException {
        return withSessionRecovery(
                ignored -> callSingle("IVirtualBox_getVersion", element("_this", virtualBoxReference)));
    }

    @Override
    public synchronized List<VirtualMachine> listMachines() throws VBoxException {
        return listMachines(machines -> {
        });
    }

    /**
     * Loads the guest list in two passes. The first pass reads only what the guest
     * tree needs and hands it to {@code progress}; the second fills in the
     * configuration fields that are not yet cached. Both passes run their per-guest
     * round trips concurrently.
     */
    @Override
    public synchronized List<VirtualMachine> listMachines(Consumer<List<VirtualMachine>> progress)
            throws VBoxException {
        return withSessionRecovery(ignored -> loadMachines(progress));
    }

    private List<VirtualMachine> loadMachines(Consumer<List<VirtualMachine>> progress) throws VBoxException {
        List<String> references = callMany("IVirtualBox_getMachines", element("_this", virtualBoxReference));
        long listingStarted = System.nanoTime();
        List<VirtualMachine> summaries = summarize(references, progress);
        LOG.info("Read the summary of {} guests in {} ms ({} round trips, {} in flight).", references.size(),
                (System.nanoTime() - listingStarted) / 1_000_000L, references.size() * 4, LIST_PARALLELISM);

        Map<String, String> refreshed = new HashMap<>();
        for (int index = 0; index < summaries.size(); index++) {
            refreshed.put(summaries.get(index).id(), references.get(index));
        }

        /*
         * Every getMachines call allocates fresh managed object references on the
         * server. Releasing the previous generation keeps the automatic refresh from
         * growing the server's object table without bound.
         */
        List<String> stale = new ArrayList<>(machineReferences.values());
        machineReferences.clear();
        machineReferences.putAll(refreshed);
        machineDetails.keySet().retainAll(refreshed.keySet());
        for (String reference : stale) {
            if (!refreshed.containsValue(reference)) {
                releaseLater(reference);
            }
        }

        progress.accept(summaries);

        List<VirtualMachine> pending = summaries.stream().filter(machine -> !machine.hasDetails()).toList();
        if (pending.isEmpty()) {
            LOG.debug("All {} guests served their configuration from the cache.", summaries.size());
            return summaries;
        }

        long detailsStarted = System.nanoTime();
        List<MachineDetails> loaded = inParallel(pending, machine -> loadDetails(refreshed.get(machine.id())));
        LOG.info("Read the configuration of {} guests in {} ms.", pending.size(),
                (System.nanoTime() - detailsStarted) / 1_000_000L);
        for (int index = 0; index < pending.size(); index++) {
            machineDetails.put(pending.get(index).id(), loaded.get(index));
        }

        List<VirtualMachine> complete = new ArrayList<>(summaries.size());
        for (VirtualMachine machine : summaries) {
            complete.add(machine.hasDetails() ? machine : withDetails(machine, machineDetails.get(machine.id())));
        }
        return complete;
    }

    /**
     * Reads the guest summaries concurrently and publishes the guests already
     * known while the rest are still loading. VirtualBox can take seconds for the
     * first access to a guest whose configuration it still has to read from disk,
     * and that must not hold back the whole list.
     */
    private List<VirtualMachine> summarize(List<String> references, Consumer<List<VirtualMachine>> progress)
            throws VBoxException {
        if (references.size() < 2) {
            List<VirtualMachine> summaries = new ArrayList<>(references.size());
            for (String reference : references) {
                summaries.add(machineSummary(reference));
            }
            return summaries;
        }

        CompletionService<IndexedSummary> completion = new ExecutorCompletionService<>(listExecutor);
        for (int index = 0; index < references.size(); index++) {
            int position = index;
            String reference = references.get(index);
            completion.submit(() -> new IndexedSummary(position, machineSummary(reference)));
        }

        VirtualMachine[] summaries = new VirtualMachine[references.size()];
        VBoxException failure = null;
        long published = System.nanoTime();
        for (int completed = 0; completed < references.size(); completed++) {
            try {
                IndexedSummary summary = completion.take().get();
                summaries[summary.index()] = summary.machine();
            } catch (ExecutionException exception) {
                if (failure == null) {
                    failure = exception.getCause() instanceof VBoxException cause
                            ? cause
                            : new VBoxException("The VirtualBox web service request failed.", exception.getCause());
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new VBoxException("VirtualBox web-service request was interrupted.", exception);
            }
            if (completed < references.size() - 1 && System.nanoTime() - published >= PARTIAL_PUBLISH_NANOS) {
                published = System.nanoTime();
                progress.accept(resolved(summaries));
            }
        }
        if (failure != null) {
            throw failure;
        }
        return Arrays.asList(summaries);
    }

    private static List<VirtualMachine> resolved(VirtualMachine[] summaries) {
        List<VirtualMachine> known = new ArrayList<>(summaries.length);
        for (VirtualMachine machine : summaries) {
            if (machine != null) {
                known.add(machine);
            }
        }
        return known;
    }

    private record IndexedSummary(int index, VirtualMachine machine) {
    }

    /** Reads the fields the guest tree renders, reusing cached configuration data. */
    private VirtualMachine machineSummary(String reference) throws VBoxException {        String id = property(reference, "IMachine_getId");
        return withDetails(new VirtualMachine(
                property(reference, "IMachine_getName"),
                id,
                property(reference, "IMachine_getState"),
                "", 0, 0,
                groupProperty(reference),
                "", ""
        ), machineDetails.get(id));
    }

    private MachineDetails loadDetails(String machineReference) throws VBoxException {
        String vrdeServer = property(machineReference, "IMachine_getVRDEServer");
        try {
            return new MachineDetails(
                    property(machineReference, "IMachine_getOSTypeId"),
                    integerProperty(machineReference, "IMachine_getMemorySize", 0),
                    integerProperty(machineReference, "IMachine_getCPUCount", 1),
                    property(machineReference, "IMachine_getDescription"),
                    callSingle("IVRDEServer_getVRDEProperty",
                            element("_this", vrdeServer) + element("key", "TCP/Ports"))
            );
        } finally {
            releaseLater(vrdeServer);
        }
    }

    private static VirtualMachine withDetails(VirtualMachine machine, MachineDetails details) {
        if (details == null) {
            return machine;
        }
        return new VirtualMachine(machine.name(), machine.id(), machine.state(), details.osType(),
                details.memoryMb(), details.cpuCount(), machine.groups(), details.description(), details.vrdePort());
    }

    /**
     * Runs one SOAP conversation per input concurrently and returns the results in
     * input order. Every task is awaited even after a failure so no managed object
     * reference is orphaned on the server.
     */
    private <S, T> List<T> inParallel(List<S> inputs, SoapTask<S, T> task) throws VBoxException {
        List<T> results = new ArrayList<>(inputs.size());
        if (inputs.size() < 2) {
            for (S input : inputs) {
                results.add(task.run(input));
            }
            return results;
        }

        List<Future<T>> futures = new ArrayList<>(inputs.size());
        for (S input : inputs) {
            futures.add(listExecutor.submit(() -> task.run(input)));
        }

        VBoxException failure = null;
        for (Future<T> future : futures) {
            try {
                results.add(future.get());
            } catch (ExecutionException exception) {
                if (failure == null) {
                    failure = exception.getCause() instanceof VBoxException cause
                            ? cause
                            : new VBoxException("The VirtualBox web service request failed.", exception.getCause());
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new VBoxException("VirtualBox web-service request was interrupted.", exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
        return results;
    }

    @FunctionalInterface
    private interface SoapTask<S, T> {
        T run(S input) throws VBoxException;
    }

    /** Configuration fields that survive a guest-list refresh unchanged. */
    private record MachineDetails(String osType, int memoryMb, int cpuCount, String description, String vrdePort) {
    }

    @Override
    public synchronized void close() throws VBoxException {
        try {
            String session = virtualBoxReference;
            virtualBoxReference = null;
            machineReferences.clear();
            machineDetails.clear();
            listExecutor.shutdownNow();
            if (session != null && !session.isBlank()) {
                // Logging off releases every managed object of this session server-side,
                // so no per-object release round trips are needed here.
                callMany("IWebsessionManager_logoff", element("refIVirtualBox", session));
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * Releases a managed object reference, ignoring failures because the caller is
     * always in a cleanup path where the original outcome matters more.
     */
    private void release(String reference) {
        if (reference == null || reference.isBlank() || virtualBoxReference == null) {
            return;
        }
        try {
            callMany("IManagedObjectRef_release", element("_this", reference));
        } catch (VBoxException exception) {
            LOG.debug("Releasing a managed object reference failed; logoff drops it anyway.", exception);
        }
    }

    /**
     * Releases a reference off the calling thread. Cleanup round trips must not
     * lengthen a guest-list refresh the user is waiting for.
     */
    private void releaseLater(String reference) {
        if (reference == null || reference.isBlank()) {
            return;
        }
        try {
            listExecutor.execute(() -> release(reference));
        } catch (RejectedExecutionException exception) {
            LOG.debug("The session is closing, so logoff releases the reference instead.", exception);
        }
    }

    @Override
    public synchronized void start(VirtualMachine machine) throws VBoxException {
        String machineReference = findMachine(machine);
        String session = newSession();
        boolean locked = false;
        try {
            String progress = callSingle("IMachine_launchVMProcess",
                    element("_this", machineReference)
                            + element("session", session)
                            + element("name", "headless")
                            // VirtualBox 6.1 requires a non-empty environment list.
                            + element("environmentChanges", "DUMMY=DUMMY"));
            locked = true;
            waitForProgress(progress, "start the guest");
        } finally {
            if (locked) {
                unlock(session);
            }
            release(session);
        }
    }

    @Override
    public synchronized void powerOff(VirtualMachine machine) throws VBoxException {
        withSession(machine, session -> {
            String progress = callSingle("IConsole_powerDown", element("_this", session.console()));
            waitForProgress(progress, "power off the guest");
            return null;
        });
    }

    @Override
    public synchronized void acpiShutdown(VirtualMachine machine) throws VBoxException {
        withSession(machine, session -> {
            callMany("IConsole_powerButton", element("_this", session.console()));
            return null;
        });
    }

    @Override
    public synchronized void saveState(VirtualMachine machine) throws VBoxException {
        withSession(machine, session -> {
            String progress = callSingle("IMachine_saveState", element("_this", session.machine()));
            waitForProgress(progress, "save the guest state");
            return null;
        });
    }

    @Override
    public synchronized void pause(VirtualMachine machine) throws VBoxException {
        withSession(machine, session -> {
            callMany("IConsole_pause", element("_this", session.console()));
            return null;
        });
    }

    @Override
    public synchronized void resume(VirtualMachine machine) throws VBoxException {
        withSession(machine, session -> {
            callMany("IConsole_resume", element("_this", session.console()));
            return null;
        });
    }

    @Override
    public synchronized void reset(VirtualMachine machine) throws VBoxException {
        withSession(machine, session -> {
            callMany("IConsole_reset", element("_this", session.console()));
            return null;
        });
    }

    @Override
    public synchronized void discardSavedState(VirtualMachine machine) throws VBoxException {
        withSession(machine, session -> {
            callMany("IMachine_discardSavedState", element("_this", session.machine()) + element("fRemoveFile", "true"));
            return null;
        });
    }

    @Override
    public synchronized void takeSnapshot(VirtualMachine machine, String name, String description) throws VBoxException {
        withSession(machine, session -> {
            // takeSnapshot declares the progress object as its [retval], so the web
            // service returns it in <returnval> and the snapshot UUID in <id>.
            String progress = callSingle("IMachine_takeSnapshot",
                    element("_this", session.machine())
                            + element("name", name)
                            + element("description", description == null ? "" : description)
                            + element("pause", "true"));
            waitForProgress(progress, "take the snapshot");
            return null;
        });
    }

    @Override
    public synchronized List<String> snapshots(VirtualMachine machine) throws VBoxException {
        String currentSnapshot = property(findMachine(machine), "IMachine_getCurrentSnapshot");
        if (currentSnapshot.isBlank()) {
            return List.of();
        }

        // getCurrentSnapshot normally identifies the active leaf, not the root.
        // First walk to the root, then traverse its complete tree so the history
        // shown to the user includes snapshots created before the active one.
        String rootSnapshot = currentSnapshot;
        String parent;
        while (!(parent = property(rootSnapshot, "ISnapshot_getParent")).isBlank()) {
            rootSnapshot = parent;
        }

        List<String> snapshots = new ArrayList<>();
        collectSnapshotNames(rootSnapshot, snapshots);
        return snapshots;
    }

    @Override
    public synchronized void restoreSnapshot(VirtualMachine machine, String snapshot) throws VBoxException {
        withSession(machine, session -> {
            String snapshotReference = callSingle("IMachine_findSnapshot",
                    element("_this", session.machine()) + element("nameOrId", snapshot));
            String progress = callSingle("IMachine_restoreSnapshot",
                    element("_this", session.machine()) + element("snapshot", snapshotReference));
            waitForProgress(progress, "restore the snapshot");
            return null;
        });
    }

    @Override
    public synchronized void deleteSnapshot(VirtualMachine machine, String snapshot) throws VBoxException {
        withSession(machine, session -> {
            String snapshotReference = callSingle("IMachine_findSnapshot",
                    element("_this", session.machine()) + element("nameOrId", snapshot));
            String snapshotId = property(snapshotReference, "ISnapshot_getId");
            String progress = callSingle("IMachine_deleteSnapshot",
                    element("_this", session.machine()) + element("id", snapshotId));
            waitForProgress(progress, "delete the snapshot");
            return null;
        });
    }

    @Override
    public synchronized Snapshot.Tree snapshotTree(VirtualMachine machine) throws VBoxException {
        String machineReference = findMachine(machine);
        String currentSnapshot = property(machineReference, "IMachine_getCurrentSnapshot");
        if (currentSnapshot.isBlank()) {
            return Snapshot.Tree.EMPTY;
        }

        // getCurrentSnapshot identifies the active leaf, so walk up to the root
        // before reading the tree: the panel shows the complete history.
        String rootSnapshot = currentSnapshot;
        String parent;
        while (!(parent = property(rootSnapshot, "ISnapshot_getParent")).isBlank()) {
            rootSnapshot = parent;
        }

        String currentId = property(currentSnapshot, "ISnapshot_getId");
        boolean modified = "true".equalsIgnoreCase(property(machineReference, "IMachine_getCurrentStateModified"));
        return new Snapshot.Tree(List.of(readSnapshot(rootSnapshot, currentId)), modified);
    }

    @Override
    public synchronized void updateSnapshot(VirtualMachine machine, String snapshotId, String name, String description)
            throws VBoxException {
        String snapshotReference = findSnapshot(machine, snapshotId);
        callMany("ISnapshot_setName", element("_this", snapshotReference) + element("name", name));
        callMany("ISnapshot_setDescription",
                element("_this", snapshotReference) + element("description", description == null ? "" : description));
    }

    @Override
    public synchronized void cloneFromSnapshot(VirtualMachine machine, String snapshotId, String name, boolean linked)
            throws VBoxException {
        String snapshotMachine = property(findSnapshot(machine, snapshotId), "ISnapshot_getMachine");
        if (snapshotMachine.isBlank()) {
            throw new VBoxException("VirtualBox did not return the machine state stored in the snapshot.");
        }
        cloneInto(machine, snapshotMachine, name, linked);
    }

    private String findSnapshot(VirtualMachine machine, String snapshotId) throws VBoxException {
        String snapshotReference = callSingle("IMachine_findSnapshot",
                element("_this", findMachine(machine)) + element("nameOrId", snapshotId));
        if (snapshotReference.isBlank()) {
            throw new VBoxException("The snapshot no longer exists on the VirtualBox server.");
        }
        return snapshotReference;
    }

    private Snapshot readSnapshot(String snapshotReference, String currentSnapshotId) throws VBoxException {
        String id = property(snapshotReference, "ISnapshot_getId");
        List<Snapshot> children = new ArrayList<>();
        for (String child : callMany("ISnapshot_getChildren", element("_this", snapshotReference))) {
            if (!child.isBlank()) {
                children.add(readSnapshot(child, currentSnapshotId));
            }
        }
        return new Snapshot(id,
                property(snapshotReference, "ISnapshot_getName"),
                property(snapshotReference, "ISnapshot_getDescription"),
                timestampOf(snapshotReference),
                "true".equalsIgnoreCase(property(snapshotReference, "ISnapshot_getOnline")),
                id.equals(currentSnapshotId),
                children);
    }

    /** VirtualBox reports the snapshot timestamp in milliseconds since the epoch. */
    private long timestampOf(String snapshotReference) throws VBoxException {
        String timestamp = property(snapshotReference, "ISnapshot_getTimeStamp").trim();
        try {
            return timestamp.isEmpty() ? 0L : Long.parseLong(timestamp);
        } catch (NumberFormatException exception) {
            LOG.debug("VirtualBox returned an unreadable snapshot timestamp '{}'.", timestamp, exception);
            return 0L;
        }
    }

    @Override
    public synchronized void createMachine(String name, String osType, int memoryMb, int cpuCount) throws VBoxException {
        registerNewMachine(name, osType, memoryMb, cpuCount);
    }

    /**
     * Creates and registers a guest, then re-resolves it through findMachine: the
     * object returned by createMachine describes an unregistered machine and
     * cannot be used to acquire a write lock afterwards.
     */
    private VirtualMachine registerNewMachine(String name, String osType, int memoryMb, int cpuCount)
            throws VBoxException {
        String unregistered = callSingle("IVirtualBox_createMachine",
                element("_this", virtualBoxReference)
                        + element("settingsFile", "")
                        + element("name", name)
                        + element("groups", "/")
                        + element("osTypeId", osType)
                        + element("flags", ""));
        callMany("IVirtualBox_registerMachine",
                element("_this", virtualBoxReference) + element("machine", unregistered));
        release(unregistered);

        String machineReference = callSingle("IVirtualBox_findMachine",
                element("_this", virtualBoxReference) + element("nameOrId", name));
        if (machineReference.isBlank()) {
            throw new VBoxException("VirtualBox did not register the new guest '" + name + "'.");
        }

        VirtualMachine created = new VirtualMachine(name, property(machineReference, "IMachine_getId"),
                "PoweredOff", osType, memoryMb, cpuCount, "/", "", "");
        machineReferences.put(created.id(), machineReference);
        updateMachineSettings(created, new MachineSettings(name, "", "/", osType, memoryMb, cpuCount,
                16, false, ""));
        return created;
    }

    @Override
    public synchronized void createMachine(NewMachineSpec specification) throws VBoxException {
        VirtualMachine created = registerNewMachine(specification.name(), specification.osType(),
                specification.memoryMb(), specification.cpuCount());
        if (specification.diskMode() == NewMachineSpec.DiskMode.NONE && specification.installerIso().isBlank()) {
            return;
        }

        String medium = "";
        if (specification.diskMode() == NewMachineSpec.DiskMode.NEW) {
            medium = callSingle("IVirtualBox_createMedium", element("_this", virtualBoxReference)
                    + element("format", specification.diskFormat())
                    + element("location", specification.name() + "." + specification.diskFormat().toLowerCase(Locale.ROOT))
                    + element("accessMode", "ReadWrite")
                    // IVirtualBox::createMedium names this parameter aDeviceTypeType.
                    + element("aDeviceTypeType", "HardDisk"));
            String progress = callSingle("IMedium_createBaseStorage", element("_this", medium)
                    + element("logicalSize", Long.toString(specification.diskSizeMb() * 1024L * 1024L))
                    + element("variant", specification.fixedDisk() ? "Fixed" : "Standard"));
            waitForProgress(progress, "create the startup disk");
        } else if (specification.diskMode() == NewMachineSpec.DiskMode.EXISTING) {
            medium = callSingle("IVirtualBox_openMedium", element("_this", virtualBoxReference)
                    + element("location", specification.existingDiskPath())
                    + element("deviceType", "HardDisk") + element("accessMode", "ReadWrite")
                    + element("forceNewUuid", "false"));
        }
        String startupMedium = medium;
        withWriteMachine(created, writableMachine -> {
            callSingle("IMachine_addStorageController", element("_this", writableMachine)
                    + element("name", specification.controllerName())
                    + element("connectionType", storageBusToSoap(specification.controllerBus())));
            if (!startupMedium.isBlank()) {
                callMany("IMachine_attachDevice", element("_this", writableMachine)
                        + element("name", specification.controllerName())
                        + element("controllerPort", Integer.toString(specification.diskPort()))
                        + element("device", Integer.toString(specification.diskDevice()))
                        + element("type", "HardDisk") + element("medium", startupMedium));
            }
            if (!specification.installerIso().isBlank()) {
                String iso = callSingle("IVirtualBox_openMedium", element("_this", virtualBoxReference)
                        + element("location", specification.installerIso()) + element("deviceType", "DVD")
                        + element("accessMode", "ReadOnly") + element("forceNewUuid", "false"));
                callMany("IMachine_attachDevice", element("_this", writableMachine)
                        + element("name", specification.controllerName())
                        + element("controllerPort", Integer.toString(specification.diskPort() + 1))
                        + element("device", "0") + element("type", "DVD") + element("medium", iso));
            }
            callMany("IMachine_saveSettings", element("_this", writableMachine));
            return null;
        });
    }

    @Override
    public synchronized void cloneMachine(VirtualMachine machine, String name, boolean linked) throws VBoxException {
        String sourceMachine = findMachine(machine);
        if (linked) {
            takeSnapshot(machine, "Base for " + machine.name() + " and " + name,
                    "Snapshot automatically created for linked clone " + name + ".");
            String snapshot = property(sourceMachine, "IMachine_getCurrentSnapshot");
            if (snapshot.isBlank()) {
                throw new VBoxException("VirtualBox did not create a base snapshot for the linked clone.");
            }
            sourceMachine = property(snapshot, "ISnapshot_getMachine");
        }
        cloneInto(machine, sourceMachine, name, linked);
    }

    /** Clones {@code sourceMachine}, which is either a guest or the machine state stored in a snapshot. */
    private void cloneInto(VirtualMachine machine, String sourceMachine, String name, boolean linked)
            throws VBoxException {
        String machineReference = findMachine(machine);
        String cloneMachine = callSingle("IVirtualBox_createMachine",
                element("_this", virtualBoxReference)
                        + element("settingsFile", "")
                        + element("name", name)
                        + element("groups", groupProperty(machineReference))
                        + element("osTypeId", property(machineReference, "IMachine_getOSTypeId"))
                        + element("flags", ""));

        try {
            String options = linked ? element("options", "Link") : "";
            String progress = callSingle("IMachine_cloneTo",
                    element("_this", sourceMachine)
                            + element("target", cloneMachine)
                            + element("mode", "MachineState")
                            + options);
            waitForProgress(progress, "clone the guest");
            callMany("IMachine_saveSettings", element("_this", cloneMachine));
            callMany("IVirtualBox_registerMachine",
                    element("_this", virtualBoxReference) + element("machine", cloneMachine));
        } catch (VBoxException exception) {
            release(cloneMachine);
            throw exception;
        }
    }

    @Override
    public synchronized void unregister(VirtualMachine machine, boolean deleteFiles) throws VBoxException {
        String machineReference = findMachine(machine);
        if (!deleteFiles) {
            callMany("IMachine_unregister",
                    element("_this", machineReference) + element("cleanupMode", "DetachAllReturnNone"));
            machineReferences.remove(machine.id());
            machineDetails.remove(machine.id());
            return;
        }

        List<String> hardDisks = callMany("IMachine_unregister",
                element("_this", machineReference) + element("cleanupMode", "DetachAllReturnHardDisksOnly"));
        StringBuilder media = new StringBuilder();
        for (String hardDisk : hardDisks) {
            if (!hardDisk.isBlank()) {
                media.append(element("media", hardDisk));
            }
        }
        String progress = callSingle("IMachine_deleteConfig",
                element("_this", machineReference) + media);
        waitForProgress(progress, "delete the guest files");
        machineReferences.remove(machine.id());
        machineDetails.remove(machine.id());
    }

    @Override
    public synchronized MachineSettings machineSettings(VirtualMachine machine) throws VBoxException {
        String machineReference = findMachine(machine);
        String graphicsAdapter = property(machineReference, "IMachine_getGraphicsAdapter");
        String vrdeServer = property(machineReference, "IMachine_getVRDEServer");
        try {
            return new MachineSettings(
                    property(machineReference, "IMachine_getName"),
                    property(machineReference, "IMachine_getDescription"),
                    groupProperty(machineReference),
                    property(machineReference, "IMachine_getOSTypeId"),
                    integerProperty(machineReference, "IMachine_getMemorySize", machine.memoryMb()),
                    integerProperty(machineReference, "IMachine_getCPUCount", machine.cpuCount()),
                    integerProperty(graphicsAdapter, "IGraphicsAdapter_getVRAMSize", 16),
                    Boolean.parseBoolean(property(vrdeServer, "IVRDEServer_getEnabled")),
                    callSingle("IVRDEServer_getVRDEProperty",
                            element("_this", vrdeServer) + element("key", "TCP/Ports"))
            );
        } finally {
            release(graphicsAdapter);
            release(vrdeServer);
        }
    }

    @Override
    public synchronized void updateMachineSettings(VirtualMachine machine, MachineSettings settings) throws VBoxException {
        withWriteMachine(machine, writableMachine -> {
            callMany("IMachine_setName", element("_this", writableMachine) + element("name", settings.name()));
            callMany("IMachine_setDescription",
                    element("_this", writableMachine) + element("description", settings.description()));
            callMany("IMachine_setGroups",
                    element("_this", writableMachine) + element("groups", settings.groups()));
            callMany("IMachine_setOSTypeId",
                    element("_this", writableMachine) + element("OSTypeId", settings.osType()));
            callMany("IMachine_setMemorySize",
                    element("_this", writableMachine) + element("memorySize", Integer.toString(settings.memoryMb())));
            callMany("IMachine_setCPUCount",
                    element("_this", writableMachine) + element("CPUCount", Integer.toString(settings.cpuCount())));

            String graphicsAdapter = property(writableMachine, "IMachine_getGraphicsAdapter");
            callMany("IGraphicsAdapter_setVRAMSize",
                    element("_this", graphicsAdapter) + element("VRAMSize", Integer.toString(settings.videoMemoryMb())));
            String vrdeServer = property(writableMachine, "IMachine_getVRDEServer");
            callMany("IVRDEServer_setEnabled",
                    element("_this", vrdeServer) + element("enabled", Boolean.toString(settings.vrdeEnabled())));
            callMany("IVRDEServer_setVRDEProperty",
                    element("_this", vrdeServer) + element("key", "TCP/Ports")
                            + element("value", settings.vrdePort().isBlank() ? "3389" : settings.vrdePort()));
            callMany("IMachine_saveSettings", element("_this", writableMachine));
            return null;
        });
    }

    @Override
    public synchronized AudioSettings audioSettings(VirtualMachine machine) throws VBoxException {
        String settings = property(findMachine(machine), "IMachine_getAudioSettings");
        String adapter = property(settings, "IAudioSettings_getAdapter");
        try {
            return new AudioSettings(
                    Boolean.parseBoolean(property(adapter, "IAudioAdapter_getEnabled")),
                    audioControllerFromSoap(property(adapter, "IAudioAdapter_getAudioController")),
                    audioDriverFromSoap(property(adapter, "IAudioAdapter_getAudioDriver")));
        } catch (IllegalArgumentException exception) {
            throw new VBoxException("VirtualBox returned unsupported audio settings.", exception);
        }
    }

    @Override
    public synchronized void updateAudioSettings(VirtualMachine machine, AudioSettings settings) throws VBoxException {
        withWriteMachine(machine, writableMachine -> {
            String audioSettings = property(writableMachine, "IMachine_getAudioSettings");
            String adapter = property(audioSettings, "IAudioSettings_getAdapter");
            callMany("IAudioAdapter_setEnabled",
                    element("_this", adapter) + element("enabled", Boolean.toString(settings.enabled())));
            callMany("IAudioAdapter_setAudioController",
                    element("_this", adapter) + element("audioController", audioControllerToSoap(settings.controller())));
            callMany("IAudioAdapter_setAudioDriver",
                    element("_this", adapter) + element("audioDriver", audioDriverToSoap(settings.driver())));
            callMany("IMachine_saveSettings", element("_this", writableMachine));
            return null;
        });
    }

    @Override
    public synchronized List<NatPortForwardRule> natPortForwardRules(VirtualMachine machine) throws VBoxException {
        String adapter = networkAdapter(findMachine(machine), 1);
        if (!"nat".equals(attachmentTypeFromSoap(property(adapter, "INetworkAdapter_getAttachmentType")))) {
            throw new VBoxException("Configure network adapter 1 for NAT before managing port-forwarding rules.");
        }
        List<NatPortForwardRule> rules = new ArrayList<>();
        for (String redirect : callMany("INATEngine_getRedirects",
                element("_this", property(adapter, "INetworkAdapter_getNATEngine")))) {
            String[] fields = redirect.split(",", -1);
            if (fields.length != 6) {
                throw new VBoxException("VirtualBox returned malformed NAT port-forwarding rule: " + redirect);
            }
            try {
                rules.add(new NatPortForwardRule(fields[0], natProtocolFromSoap(fields[1]), fields[2],
                        Integer.parseInt(fields[3]), fields[4], Integer.parseInt(fields[5])));
            } catch (IllegalArgumentException exception) {
                throw new VBoxException("VirtualBox returned an invalid NAT port-forwarding rule.", exception);
            }
        }
        return rules;
    }

    /**
     * INATEngine::redirects encodes the protocol as the numeric NATProtocol value
     * (UDP = 0, TCP = 1); older builds spelled it out.
     */
    private static String natProtocolFromSoap(String value) {
        return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) {
            case "0", "udp" -> "udp";
            case "1", "tcp" -> "tcp";
            default -> throw new IllegalArgumentException("Unsupported NAT protocol: " + value);
        };
    }

    @Override
    public synchronized void addNatPortForwardRule(VirtualMachine machine, NatPortForwardRule rule) throws VBoxException {
        withWriteMachine(machine, writableMachine -> {
            String adapter = networkAdapter(writableMachine, 1);
            if (!"nat".equals(attachmentTypeFromSoap(property(adapter, "INetworkAdapter_getAttachmentType")))) {
                throw new VBoxException("Configure network adapter 1 for NAT before adding port-forwarding rules.");
            }
            String engine = property(adapter, "INetworkAdapter_getNATEngine");
            callMany("INATEngine_addRedirect", element("_this", engine)
                    + element("name", rule.name())
                    + element("proto", rule.protocol().toUpperCase(Locale.ROOT))
                    + element("hostIP", rule.hostIp())
                    + element("hostPort", Integer.toString(rule.hostPort()))
                    + element("guestIP", rule.guestIp())
                    + element("guestPort", Integer.toString(rule.guestPort())));
            callMany("IMachine_saveSettings", element("_this", writableMachine));
            return null;
        });
    }

    @Override
    public synchronized void removeNatPortForwardRule(VirtualMachine machine, String ruleName) throws VBoxException {
        if (ruleName == null || ruleName.isBlank()) {
            throw new VBoxException("Select a NAT port-forwarding rule to remove.");
        }
        withWriteMachine(machine, writableMachine -> {
            String adapter = networkAdapter(writableMachine, 1);
            if (!"nat".equals(attachmentTypeFromSoap(property(adapter, "INetworkAdapter_getAttachmentType")))) {
                throw new VBoxException("Configure network adapter 1 for NAT before removing port-forwarding rules.");
            }
            callMany("INATEngine_removeRedirect", element("_this",
                    property(adapter, "INetworkAdapter_getNATEngine")) + element("name", ruleName.trim()));
            callMany("IMachine_saveSettings", element("_this", writableMachine));
            return null;
        });
    }

    @Override
    public synchronized List<SharedFolder> sharedFolders(VirtualMachine machine) throws VBoxException {
        List<SharedFolder> folders = new ArrayList<>();
        for (String folder : callMany("IMachine_getSharedFolders", element("_this", findMachine(machine)))) {
            folders.add(new SharedFolder(
                    property(folder, "ISharedFolder_getName"),
                    property(folder, "ISharedFolder_getHostPath"),
                    !Boolean.parseBoolean(property(folder, "ISharedFolder_getWritable")),
                    Boolean.parseBoolean(property(folder, "ISharedFolder_getAutoMount"))));
        }
        return folders;
    }

    @Override
    public synchronized String sharedFolderInformation(VirtualMachine machine) throws VBoxException {
        List<SharedFolder> folders = sharedFolders(machine);
        if (folders.isEmpty()) {
            return "(No shared folders configured.)";
        }
        StringBuilder result = new StringBuilder("Name                    Host path");
        for (SharedFolder folder : folders) {
            result.append(System.lineSeparator())
                    .append(String.format("%-23s %s", folder.name(), folder.hostPath()));
        }
        return result.toString();
    }

    @Override
    public synchronized void addSharedFolder(VirtualMachine machine, String name, String hostPath, boolean readOnly,
                                boolean autoMount) throws VBoxException {
        if (name == null || name.isBlank() || hostPath == null || hostPath.isBlank()) {
            throw new VBoxException("A shared-folder name and host path are required.");
        }
        withWriteMachine(machine, writableMachine -> {
            callMany("IMachine_createSharedFolder", element("_this", writableMachine)
                    + element("name", name.trim())
                    + element("hostPath", hostPath.trim())
                    + element("writable", Boolean.toString(!readOnly))
                    + element("automount", Boolean.toString(autoMount))
                    + element("autoMountPoint", ""));
            callMany("IMachine_saveSettings", element("_this", writableMachine));
            return null;
        });
    }

    @Override
    public synchronized void removeSharedFolder(VirtualMachine machine, String name) throws VBoxException {
        if (name == null || name.isBlank()) {
            throw new VBoxException("Select a shared folder to remove.");
        }
        withWriteMachine(machine, writableMachine -> {
            callMany("IMachine_removeSharedFolder",
                    element("_this", writableMachine) + element("name", name.trim()));
            callMany("IMachine_saveSettings", element("_this", writableMachine));
            return null;
        });
    }

    @Override
    public synchronized NetworkAdapterSettings networkAdapterSettings(VirtualMachine machine, int adapterIndex)
            throws VBoxException {
        validateNetworkAdapterIndex(adapterIndex);
        String adapter = networkAdapter(findMachine(machine), adapterIndex);
        String attachment = attachmentTypeFromSoap(property(adapter, "INetworkAdapter_getAttachmentType"));
        String adapterName = switch (attachment) {
            case "bridged" -> property(adapter, "INetworkAdapter_getBridgedInterface");
            case "hostonly" -> property(adapter, "INetworkAdapter_getHostOnlyInterface");
            case "intnet" -> property(adapter, "INetworkAdapter_getInternalNetwork");
            default -> "";
        };
        try {
            return new NetworkAdapterSettings(
                    Boolean.parseBoolean(property(adapter, "INetworkAdapter_getEnabled")),
                    attachment,
                    adapterName);
        } catch (IllegalArgumentException exception) {
            throw new VBoxException("VirtualBox returned unsupported network adapter settings.", exception);
        }
    }

    @Override
    public synchronized void updateNetworkAdapterSettings(VirtualMachine machine, int adapterIndex,
                                             NetworkAdapterSettings settings) throws VBoxException {
        validateNetworkAdapterIndex(adapterIndex);
        withWriteMachine(machine, writableMachine -> {
            String adapter = networkAdapter(writableMachine, adapterIndex);
            callMany("INetworkAdapter_setEnabled",
                    element("_this", adapter) + element("enabled", Boolean.toString(settings.enabled())));
            callMany("INetworkAdapter_setAttachmentType",
                    element("_this", adapter)
                            + element("attachmentType", attachmentTypeToSoap(settings.attachmentType())));
            switch (settings.attachmentType()) {
                case "bridged" -> callMany("INetworkAdapter_setBridgedInterface",
                        element("_this", adapter) + element("bridgedInterface", settings.adapterName()));
                case "hostonly" -> callMany("INetworkAdapter_setHostOnlyInterface",
                        element("_this", adapter) + element("hostOnlyInterface", settings.adapterName()));
                case "intnet" -> callMany("INetworkAdapter_setInternalNetwork",
                        element("_this", adapter) + element("internalNetwork", settings.adapterName()));
                default -> {
                    // NAT and disconnected adapters do not use a named network.
                }
            }
            callMany("IMachine_saveSettings", element("_this", writableMachine));
            return null;
        });
    }

    @Override
    public synchronized void attachOpticalMedium(VirtualMachine machine, String controllerName, int port, int device,
                                    String isoPath) throws VBoxException {
        withWriteMachine(machine, writableMachine -> {
            String medium = "";
            if (isoPath != null && !isoPath.isBlank()) {
                medium = callSingle("IVirtualBox_openMedium", element("_this", virtualBoxReference)
                        + element("location", isoPath.trim()) + element("deviceType", "DVD")
                        + element("accessMode", "ReadOnly") + element("forceNewUuid", "false"));
            }
            String slot = element("name", controllerName)
                    + element("controllerPort", Integer.toString(port))
                    + element("device", Integer.toString(device));
            // mountMedium only works on an existing drive, so create one on demand.
            if (callStructs("IMachine_getMediumAttachment",
                    element("_this", writableMachine) + slot).isEmpty()) {
                callMany("IMachine_attachDevice", element("_this", writableMachine) + slot
                        + element("type", "DVD") + element("medium", medium));
            } else {
                callMany("IMachine_mountMedium", element("_this", writableMachine) + slot
                        + element("medium", medium) + element("force", "true"));
            }
            callMany("IMachine_saveSettings", element("_this", writableMachine));
            return null;
        });
    }

    @Override
    public synchronized List<StorageController> storageLayout(VirtualMachine machine) throws VBoxException {
        String machineReference = findMachine(machine);
        List<StorageController> controllers = new ArrayList<>();
        for (String controller : callMany("IMachine_getStorageControllers", element("_this", machineReference))) {
            try {
                String name = property(controller, "IStorageController_getName");
                controllers.add(new StorageController(name,
                        storageBusFromSoap(property(controller, "IStorageController_getBus")),
                        property(controller, "IStorageController_getControllerType"),
                        integerProperty(controller, "IStorageController_getPortCount", 1),
                        Boolean.parseBoolean(property(controller, "IStorageController_getUseHostIOCache")),
                        Boolean.parseBoolean(property(controller, "IStorageController_getBootable")),
                        controllerAttachments(machineReference, name)));
            } catch (IllegalArgumentException exception) {
                throw new VBoxException("VirtualBox returned an unsupported storage controller.", exception);
            } finally {
                release(controller);
            }
        }
        return controllers;
    }

    private List<StorageAttachment> controllerAttachments(String machineReference, String controllerName)
            throws VBoxException {
        List<StorageAttachment> attachments = new ArrayList<>();
        for (Map<String, String> attachment : callStructs("IMachine_getMediumAttachmentsOfController",
                element("_this", machineReference) + element("name", controllerName))) {
            String deviceType = deviceTypeFromSoap(attachment.getOrDefault("type", ""));
            if (deviceType == null) {
                continue;
            }
            String medium = attachment.getOrDefault("medium", "");
            String location = "";
            if (!medium.isBlank()) {
                location = property(medium, "IMedium_getLocation");
                release(medium);
            }
            attachments.add(new StorageAttachment(
                    optionalInt(attachment.get("port"), 0),
                    optionalInt(attachment.get("device"), 0),
                    deviceType, location));
        }
        attachments.sort(Comparator.comparingInt(StorageAttachment::port)
                .thenComparingInt(StorageAttachment::device));
        return attachments;
    }

    @Override
    public synchronized void updateStorageController(VirtualMachine machine, String currentName, StorageController updated)
            throws VBoxException {
        withWriteMachine(machine, writableMachine -> {
            String controller = callSingle("IMachine_getStorageControllerByName",
                    element("_this", writableMachine) + element("name", currentName));
            if (controller.isBlank()) {
                throw new VBoxException("The storage controller '" + currentName + "' no longer exists.");
            }
            try {
                if (!updated.controllerType().isBlank()) {
                    callMany("IStorageController_setControllerType", element("_this", controller)
                            + element("controllerType", updated.controllerType()));
                }
                if (updated.portCount() > 0) {
                    callMany("IStorageController_setPortCount", element("_this", controller)
                            + element("portCount", Integer.toString(updated.portCount())));
                }
                if (updated.useHostIoCache() != null) {
                    callMany("IStorageController_setUseHostIOCache", element("_this", controller)
                            + element("useHostIOCache", Boolean.toString(updated.useHostIoCache())));
                }
                if (!currentName.equals(updated.name())) {
                    callMany("IStorageController_setName", element("_this", controller)
                            + element("name", updated.name()));
                }
            } finally {
                release(controller);
            }
            callMany("IMachine_saveSettings", element("_this", writableMachine));
            return null;
        });
    }

    private static String storageBusFromSoap(String value) {
        return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) {
            case "ide" -> "ide";
            case "sata" -> "sata";
            case "scsi" -> "scsi";
            case "sas" -> "sas";
            case "floppy" -> "floppy";
            case "usb" -> "usb";
            case "pcie" -> "pcie";
            case "virtioscsi" -> "virtio";
            default -> throw new IllegalArgumentException("Unsupported storage bus: " + value);
        };
    }

    /** Returns {@code null} for DeviceType_Null placeholders. */
    private static String deviceTypeFromSoap(String value) {
        return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) {
            case "harddisk" -> "hdd";
            case "dvd" -> "dvd";
            case "floppy" -> "fdd";
            default -> null;
        };
    }

    @Override
    public synchronized void addStorageController(VirtualMachine machine, StorageControllerSpec controller) throws VBoxException {
        withWriteMachine(machine, writableMachine -> {
            callSingle("IMachine_addStorageController", element("_this", writableMachine)
                    + element("name", controller.name()) + element("connectionType", storageBusToSoap(controller.bus())));
            callMany("IMachine_saveSettings", element("_this", writableMachine));
            return null;
        });
    }

    @Override
    public synchronized void removeStorageController(VirtualMachine machine, String controllerName) throws VBoxException {
        withWriteMachine(machine, writableMachine -> {
            callMany("IMachine_removeStorageController", element("_this", writableMachine)
                    + element("name", controllerName));
            callMany("IMachine_saveSettings", element("_this", writableMachine));
            return null;
        });
    }

    @Override
    public synchronized void attachHardDisk(VirtualMachine machine, String controllerName, int port, int device,
                               String mediumPath) throws VBoxException {
        withWriteMachine(machine, writableMachine -> {
            String medium = callSingle("IVirtualBox_openMedium", element("_this", virtualBoxReference)
                    + element("location", mediumPath) + element("deviceType", "HardDisk")
                    + element("accessMode", "ReadWrite") + element("forceNewUuid", "false"));
            callMany("IMachine_attachDevice", element("_this", writableMachine)
                    + element("name", controllerName) + element("controllerPort", Integer.toString(port))
                    + element("device", Integer.toString(device)) + element("type", "HardDisk")
                    + element("medium", medium));
            callMany("IMachine_saveSettings", element("_this", writableMachine));
            return null;
        });
    }

    @Override
    public synchronized void detachStorageMedium(VirtualMachine machine, String controllerName, int port, int device)
            throws VBoxException {
        withWriteMachine(machine, writableMachine -> {
            callMany("IMachine_detachDevice", element("_this", writableMachine)
                    + element("name", controllerName) + element("controllerPort", Integer.toString(port))
                    + element("device", Integer.toString(device)));
            callMany("IMachine_saveSettings", element("_this", writableMachine));
            return null;
        });
    }

    @Override
    public synchronized SerialPortSettings serialPortSettings(VirtualMachine machine, int portIndex) throws VBoxException {
        validateSerialPortIndex(portIndex);
        String port = serialPort(findMachine(machine), portIndex);
        try {
            return new SerialPortSettings(
                    Boolean.parseBoolean(property(port, "ISerialPort_getEnabled")),
                    toHexAddress(unsignedProperty(port, "ISerialPort_getIOAddress")),
                    unsignedProperty(port, "ISerialPort_getIRQ"),
                    serialModeFromSoap(property(port, "ISerialPort_getHostMode")));
        } catch (IllegalArgumentException exception) {
            throw new VBoxException("VirtualBox returned unsupported UART " + portIndex + " configuration.", exception);
        }
    }

    @Override
    public synchronized void updateSerialPortSettings(VirtualMachine machine, int portIndex, SerialPortSettings settings)
            throws VBoxException {
        validateSerialPortIndex(portIndex);
        withWriteMachine(machine, writableMachine -> {
            String port = serialPort(writableMachine, portIndex);
            callMany("ISerialPort_setEnabled",
                    element("_this", port) + element("enabled", Boolean.toString(settings.enabled())));
            callMany("ISerialPort_setIOAddress",
                    element("_this", port) + element("IOAddress", Integer.toString(parseAddress(settings.ioBase()))));
            callMany("ISerialPort_setIRQ",
                    element("_this", port) + element("IRQ", Integer.toString(settings.irq())));
            callMany("ISerialPort_setHostMode",
                    element("_this", port) + element("hostMode", serialModeToSoap(settings.mode())));
            callMany("IMachine_saveSettings", element("_this", writableMachine));
            return null;
        });
    }

    @Override
    public synchronized ParallelPortSettings parallelPortSettings(VirtualMachine machine) throws VBoxException {
        String port = callSingle("IMachine_getParallelPort",
                element("_this", findMachine(machine)) + element("slot", "0"));
        try {
            return new ParallelPortSettings(
                    Boolean.parseBoolean(property(port, "IParallelPort_getEnabled")),
                    toHexAddress(unsignedProperty(port, "IParallelPort_getIOBase")),
                    unsignedProperty(port, "IParallelPort_getIRQ"));
        } catch (IllegalArgumentException exception) {
            throw new VBoxException("VirtualBox returned unsupported parallel-port configuration.", exception);
        }
    }

    @Override
    public synchronized void updateParallelPortSettings(VirtualMachine machine, ParallelPortSettings settings)
            throws VBoxException {
        withWriteMachine(machine, writableMachine -> {
            String port = callSingle("IMachine_getParallelPort",
                    element("_this", writableMachine) + element("slot", "0"));
            callMany("IParallelPort_setEnabled",
                    element("_this", port) + element("enabled", Boolean.toString(settings.enabled())));
            callMany("IParallelPort_setIOBase",
                    element("_this", port) + element("IOBase", Integer.toString(parseAddress(settings.ioBase()))));
            callMany("IParallelPort_setIRQ",
                    element("_this", port) + element("IRQ", Integer.toString(settings.irq())));
            callMany("IMachine_saveSettings", element("_this", writableMachine));
            return null;
        });
    }

    @Override
    public synchronized UsbControllerSettings usbControllerSettings(VirtualMachine machine) throws VBoxException {
        List<String> controllers = callMany("IMachine_getUSBControllers", element("_this", findMachine(machine)));
        if (controllers.isEmpty()) {
            return new UsbControllerSettings(false, "none");
        }
        try {
            // USBStandard is a BCD version number; the controller kind lives in `type`.
            return new UsbControllerSettings(true,
                    usbControllerFromSoap(property(controllers.get(0), "IUSBController_getType")));
        } catch (IllegalArgumentException exception) {
            throw new VBoxException("VirtualBox returned an unsupported USB controller configuration.", exception);
        }
    }

    @Override
    public synchronized void updateUsbControllerSettings(VirtualMachine machine, UsbControllerSettings settings)
            throws VBoxException {
        withWriteMachine(machine, writableMachine -> {
            for (String controller : callMany("IMachine_getUSBControllers", element("_this", writableMachine))) {
                callMany("IMachine_removeUSBController",
                        element("_this", writableMachine)
                                + element("name", property(controller, "IUSBController_getName")));
            }
            if (settings.enabled()) {
                callSingle("IMachine_addUSBController",
                        element("_this", writableMachine)
                                + element("name", "USB Controller")
                                + element("type", usbControllerToSoap(settings.controller())));
            }
            callMany("IMachine_saveSettings", element("_this", writableMachine));
            return null;
        });
    }

    @Override
    public synchronized GuestIntegrationSettings guestIntegrationSettings(VirtualMachine machine) throws VBoxException {
        String machineReference = findMachine(machine);
        try {
            String clipboardMode = integrationModeFromSoap(property(machineReference, "IMachine_getClipboardMode"));
            String dragAndDropMode = dragAndDropSupported == Boolean.FALSE
                    ? "disabled"
                    : optionalDragAndDropMode(machineReference);
            return new GuestIntegrationSettings(clipboardMode, dragAndDropMode);
        } catch (IllegalArgumentException exception) {
            throw new VBoxException("VirtualBox returned unsupported guest integration settings.", exception);
        }
    }

    @Override
    public synchronized void updateGuestIntegrationSettings(VirtualMachine machine, GuestIntegrationSettings settings)
            throws VBoxException {
        withWriteMachine(machine, writableMachine -> {
            callMany("IMachine_setClipboardMode",
                    element("_this", writableMachine)
                            + element("clipboardMode", integrationModeToSoap(settings.clipboardMode())));
            if (dragAndDropSupported != Boolean.FALSE) {
                String mode = integrationModeToSoap(settings.dragAndDropMode());
                try {
                    callMany("IMachine_setDnDMode",
                            element("_this", writableMachine) + element("dnDMode", mode));
                    dragAndDropSupported = true;
                } catch (VBoxException exception) {
                    if (!isUnsupportedDragAndDropOperation(exception)) {
                        throw exception;
                    }
                    try {
                        callMany("IMachine_setDragAndDropMode",
                                element("_this", writableMachine) + element("dragAndDropMode", mode));
                        dragAndDropSupported = true;
                    } catch (VBoxException legacyFailure) {
                        if (!isUnsupportedDragAndDropOperation(legacyFailure)) {
                            throw legacyFailure;
                        }
                        LOG.info("This VirtualBox server has no drag-and-drop API; the setting is ignored.");
                        dragAndDropSupported = false;
                    }
                }
            }
            callMany("IMachine_saveSettings", element("_this", writableMachine));
            return null;
        });
    }

    /**
     * The IMachine attribute was renamed from {@code dragAndDropMode} to
     * {@code dnDMode}; try the current name first and fall back for old servers.
     */
    private String optionalDragAndDropMode(String machineReference) throws VBoxException {
        try {
            String mode = integrationModeFromSoap(property(machineReference, "IMachine_getDnDMode"));
            dragAndDropSupported = true;
            return mode;
        } catch (VBoxException exception) {
            if (!isUnsupportedDragAndDropOperation(exception)) {
                throw exception;
            }
        }
        try {
            String mode = integrationModeFromSoap(property(machineReference, "IMachine_getDragAndDropMode"));
            dragAndDropSupported = true;
            return mode;
        } catch (VBoxException exception) {
            if (!isUnsupportedDragAndDropOperation(exception)) {
                throw exception;
            }
            LOG.info("This VirtualBox server has no drag-and-drop API; reporting it as disabled.");
            dragAndDropSupported = false;
            return "disabled";
        }
    }

    private static boolean isUnsupportedDragAndDropOperation(VBoxException exception) {
        String message = exception.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return (normalized.contains("dndmode") || normalized.contains("draganddropmode"))
                && (normalized.contains("not implemented")
                || normalized.contains("not recognized")
                || normalized.contains("unknown")
                || normalized.contains("no such operation")
                || normalized.contains("validation constraint violation"));
    }

    @Override
    public synchronized String guestLog(VirtualMachine machine, int logIndex) throws VBoxException {
        /*
         * IMachine::readLog returns octet[], so the web service base64-encodes it,
         * and the server caps each chunk. Read repeatedly until the log ends.
         */
        String machineReference = findMachine(machine);
        String index = Integer.toString(Math.max(0, logIndex));
        StringBuilder log = new StringBuilder();
        long offset = 0;
        while (log.length() < MAX_GUEST_LOG_CHARACTERS) {
            String encoded = callSingle("IMachine_readLog",
                    element("_this", machineReference)
                            + element("idx", index)
                            + element("offset", Long.toString(offset))
                            + element("size", Integer.toString(GUEST_LOG_CHUNK_BYTES)));
            byte[] chunk;
            try {
                chunk = Base64.getMimeDecoder().decode(encoded);
            } catch (IllegalArgumentException exception) {
                throw new VBoxException("VirtualBox returned an unreadable guest log.", exception);
            }
            if (chunk.length == 0) {
                break;
            }
            log.append(new String(chunk, StandardCharsets.UTF_8));
            offset += chunk.length;
            if (chunk.length < GUEST_LOG_CHUNK_BYTES) {
                break;
            }
        }
        return log.toString();
    }

    @Override
    public synchronized String serverInformation() throws VBoxException {
        String host = property(virtualBoxReference, "IVirtualBox_getHost");
        return "VirtualBox version: " + version()
                + System.lineSeparator()
                + "Server endpoint: " + endpoint
                + System.lineSeparator()
                + "Host memory: " + property(host, "IHost_getMemorySize") + " MB"
                + System.lineSeparator()
                + "Processor cores: " + property(host, "IHost_getProcessorCount");
    }

    @Override
    public synchronized HostMemory hostMemory() throws VBoxException {
        return withSessionRecovery(ignored -> {
            String host = property(virtualBoxReference, "IVirtualBox_getHost");
            try {
                return new HostMemory(integerProperty(host, "IHost_getMemorySize", 0),
                        integerProperty(host, "IHost_getMemoryAvailable", 0));
            } finally {
                release(host);
            }
        });
    }

    @Override
    public synchronized void sendCtrlAltDelete(VirtualMachine machine) throws VBoxException {
        withSession(machine, session -> {
            String keyboard = property(session.console(), "IConsole_getKeyboard");
            callMany("IKeyboard_putCAD", element("_this", keyboard));
            return null;
        });
    }

    @Override
    public synchronized void sendScancodes(VirtualMachine machine, int... scancodes) throws VBoxException {
        withSession(machine, session -> {
            String keyboard = property(session.console(), "IConsole_getKeyboard");
            for (int scancode : scancodes) {
                callMany("IKeyboard_putScancode",
                        element("_this", keyboard) + element("scancode", Integer.toString(scancode)));
            }
            return null;
        });
    }

    @Override
    public synchronized void releaseKeys(VirtualMachine machine) throws VBoxException {
        withSession(machine, session -> {
            String keyboard = property(session.console(), "IConsole_getKeyboard");
            callMany("IKeyboard_releaseKeys", element("_this", keyboard));
            return null;
        });
    }

    @Override
    public synchronized byte[] screenshotPng(VirtualMachine machine) throws VBoxException {
        return withSession(machine, session -> {
            String display = property(session.console(), "IConsole_getDisplay");
            Map<String, String> resolution = callFields("IDisplay_getScreenResolution",
                    element("_this", display) + element("screenId", "0"), "width", "height");
            int width = parseField(resolution.get("width"), "screen width");
            int height = parseField(resolution.get("height"), "screen height");
            if (width <= 0 || height <= 0) {
                throw new VBoxException("The guest display does not have a usable screen resolution.");
            }
            String encodedPng = callSingle("IDisplay_takeScreenShotToArray",
                    element("_this", display)
                            + element("screenId", "0")
                            + element("width", Integer.toString(width))
                            + element("height", Integer.toString(height))
                            + element("bitmapFormat", "PNG"));
            try {
                return Base64.getMimeDecoder().decode(encodedPng);
            } catch (IllegalArgumentException exception) {
                throw new VBoxException("VirtualBox returned an invalid PNG screenshot.", exception);
            }
        });
    }

    @Override
    public synchronized String showDisplay(VirtualMachine machine) throws VBoxException {
        String machineReference = findMachine(machine);

        String session = newSession();
        boolean locked = false;
        String vrdeServer = "";
        try {
            callMany("IMachine_lockMachine",
                    element("_this", machineReference) + element("session", session) + element("lockType", "Shared"));
            locked = true;
            String console = callSingle("ISession_getConsole", element("_this", session));
            int port = waitForDisplayPort(console, machine.name());

            vrdeServer = property(machineReference, "IMachine_getVRDEServer");
            String extensionPack = property(vrdeServer, "IVRDEServer_getVRDEExtPack");
            String bindAddress = callSingle("IVRDEServer_getVRDEProperty",
                    element("_this", vrdeServer) + element("key", "TCP/Address")).trim();
            String host = bindAddress.isBlank() ? endpoint.getHost() : bindAddress;
            return launchDisplayClient(machine, host, port, extensionPack);
        } finally {
            release(vrdeServer);
            if (locked) {
                try {
                    unlock(session);
                } catch (VBoxException exception) {
                    LOG.debug("Unlocking after launching the display client failed.", exception);
                }
            }
            release(session);
        }
    }

    private String newSession() throws VBoxException {
        return callSingle("IWebsessionManager_getSessionObject",
                element("refIVirtualBox", virtualBoxReference));
    }

    private <T> T withWriteMachine(VirtualMachine machine, WriteMachineOperation<T> operation) throws VBoxException {
        String session = newSession();
        boolean locked = false;
        VBoxException failure = null;
        // A write lock exists to change the configuration, so the cached fields are
        // no longer trustworthy regardless of the outcome.
        machineDetails.remove(machine.id());
        try {
            callMany("IMachine_lockMachine",
                    element("_this", findMachine(machine))
                            + element("session", session)
                            + element("lockType", "Write"));
            locked = true;
            return operation.run(callSingle("ISession_getMachine", element("_this", session)));
        } catch (VBoxException exception) {
            failure = exception;
            throw exception;
        } finally {
            if (locked) {
                unlockAfter(session, failure);
            }
            release(session);
        }
    }

    private <T> T withSession(VirtualMachine machine, SessionOperation<T> operation) throws VBoxException {
        String machineReference = findMachine(machine);
        String session = newSession();
        boolean locked = false;
        VBoxException failure = null;
        try {
            /*
             * This mirrors RemoteBox 3.7's get_session: an inactive machine
             * receives a VM lock (which yields a writable machine), while a
             * running machine is accessed through a Shared lock. In particular,
             * snapshots must remain available while a guest is running.
             */
            String sessionState = property(machineReference, "IMachine_getSessionState");
            String lockType = "Unlocked".equalsIgnoreCase(sessionState) ? "VM" : "Shared";
            callMany("IMachine_lockMachine",
                    element("_this", machineReference)
                            + element("session", session)
                            + element("lockType", lockType));
            locked = true;
            String writableMachine = callSingle("ISession_getMachine", element("_this", session));
            String console = callSingle("ISession_getConsole", element("_this", session));
            return operation.run(new MachineSession(session, writableMachine, console));
        } catch (VBoxException exception) {
            failure = exception;
            throw exception;
        } finally {
            if (locked) {
                unlockAfter(session, failure);
            }
            release(session);
        }
    }

    private void unlock(String session) throws VBoxException {
        callMany("ISession_unlockMachine", element("_this", session));
    }

    /** Keeps the failure that closed the session while still reporting the failed unlock. */
    private void unlockAfter(String session, VBoxException failure) throws VBoxException {
        try {
            unlock(session);
        } catch (VBoxException unlockFailure) {
            LOG.warn("Unlocking the guest session failed.", unlockFailure);
            if (failure != null) {
                failure.addSuppressed(unlockFailure);
            } else {
                release(session);
                throw unlockFailure;
            }
        }
    }

    private void waitForProgress(String progress, String action) throws VBoxException {
        if (progress == null || progress.isBlank()) {
            throw new VBoxException("VirtualBox did not return progress information to " + action + ".");
        }

        try {
            long deadline = System.nanoTime() + Duration.ofMinutes(15).toNanos();
            while (!Boolean.parseBoolean(property(progress, "IProgress_getCompleted"))) {
                if (System.nanoTime() >= deadline) {
                    throw new VBoxException("Timed out waiting for VirtualBox to " + action + ".");
                }
                // Let the server block instead of polling it in a tight loop.
                callMany("IProgress_waitForCompletion",
                        element("_this", progress) + element("timeout", "1000"));
            }

            int resultCode = integerProperty(progress, "IProgress_getResultCode", 0);
            if (resultCode != 0) {
                String detail = "";
                String errorReference = property(progress, "IProgress_getErrorInfo");
                if (!errorReference.isBlank()) {
                    detail = property(errorReference, "IVirtualBoxErrorInfo_getText").trim();
                    release(errorReference);
                }
                throw new VBoxException("VirtualBox could not " + action + " (result " + resultCode + ")"
                        + (detail.isBlank() ? "." : ": " + detail));
            }
        } finally {
            release(progress);
        }
    }

    private void collectSnapshotNames(String snapshotReference, List<String> snapshots) throws VBoxException {
        snapshots.add(property(snapshotReference, "ISnapshot_getName"));
        for (String child : callMany("ISnapshot_getChildren", element("_this", snapshotReference))) {
            if (!child.isBlank()) {
                collectSnapshotNames(child, snapshots);
            }
        }
    }

    @FunctionalInterface
    private interface WriteMachineOperation<T> {
        T run(String writableMachine) throws VBoxException;
    }

    @FunctionalInterface
    private interface SessionOperation<T> {
        T run(MachineSession session) throws VBoxException;
    }

    private record MachineSession(String reference, String machine, String console) {
    }

    private String findMachine(VirtualMachine machine) throws VBoxException {
        /*
         * getMachines already returns the opaque managed-object reference needed
         * by all IMachine operations. Reusing it is essential: unlike a UUID,
         * this reference is valid for this authenticated SOAP session.
         */
        String machineReference = machineReferences.get(machine.id());
        if (machineReference != null && !machineReference.isBlank()) {
            return machineReference;
        }

        // The table can be stale after a server-side change. Refresh once before
        // reporting that the guest no longer exists.
        listMachines();
        machineReference = machineReferences.get(machine.id());
        if (machineReference == null || machineReference.isBlank()) {
            throw new VBoxException("The selected guest is no longer available on the VirtualBox server. "
                    + "Refresh the guest list and try again.");
        }
        return machineReference;
    }

    private String property(String machineReference, String operation) throws VBoxException {
        return callSingle(operation, element("_this", machineReference));
    }

    private int integerProperty(String machineReference, String operation, int fallback) throws VBoxException {
        try {
            return Integer.parseInt(property(machineReference, operation));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String groupProperty(String machineReference) throws VBoxException {
        List<String> groups = callMany("IMachine_getGroups", element("_this", machineReference));
        return groups.isEmpty() ? "/" : groups.get(0);
    }

    private String networkAdapter(String machineReference, int adapterIndex) throws VBoxException {
        return callSingle("IMachine_getNetworkAdapter",
                element("_this", machineReference) + element("slot", Integer.toString(adapterIndex - 1)));
    }

    private static void validateNetworkAdapterIndex(int adapterIndex) throws VBoxException {
        if (adapterIndex < 1 || adapterIndex > 8) {
            throw new VBoxException("Network adapter index must be between 1 and 8.");
        }
    }

    private String serialPort(String machineReference, int portIndex) throws VBoxException {
        return callSingle("IMachine_getSerialPort",
                element("_this", machineReference) + element("slot", Integer.toString(portIndex - 1)));
    }

    private static void validateSerialPortIndex(int portIndex) throws VBoxException {
        if (portIndex < 1 || portIndex > 2) {
            throw new VBoxException("VirtualBox serial-port index must be 1 or 2.");
        }
    }

    private int unsignedProperty(String reference, String operation) throws VBoxException {
        try {
            return Integer.parseUnsignedInt(property(reference, operation));
        } catch (NumberFormatException exception) {
            throw new VBoxException("VirtualBox returned an invalid unsigned integer.", exception);
        }
    }

    private static String toHexAddress(int address) {
        return String.format("0x%x", address);
    }

    private static int parseAddress(String address) {
        return Integer.decode(address);
    }

    private static String serialModeFromSoap(String value) {
        if ("Disconnected".equalsIgnoreCase(value)) {
            return "disconnected";
        }
        throw new IllegalArgumentException("Unsupported serial host mode: " + value);
    }
    private static String serialModeToSoap(String value) throws VBoxException {
        if ("disconnected".equals(value)) {
            return "Disconnected";
        }
        throw new VBoxException("Unsupported serial host mode: " + value);
    }

    private static String storageBusToSoap(String bus) throws VBoxException {
        return switch (bus == null ? "" : bus.toLowerCase(Locale.ROOT)) {
            case "ide" -> "IDE";
            case "sata" -> "SATA";
            case "scsi" -> "SCSI";
            case "sas" -> "SAS";
            case "pcie" -> "PCIe";
            case "floppy" -> "Floppy";
            case "usb" -> "USB";
            case "virtio", "virtioscsi" -> "VirtioSCSI";
            default -> throw new VBoxException("Unsupported storage controller bus: " + bus);
        };
    }

    private static String usbControllerFromSoap(String value) {
        return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) {
            case "usb11", "usb1.1", "ohci" -> "ohci";
            case "usb2", "usb2.0", "ehci" -> "ehci";
            case "usb3", "usb3.0", "xhci" -> "xhci";
            default -> throw new IllegalArgumentException("Unsupported USB controller type: " + value);
        };
    }

    private static String usbControllerToSoap(String value) throws VBoxException {
        return switch (value) {
            case "ohci" -> "OHCI";
            case "ehci" -> "EHCI";
            case "xhci" -> "XHCI";
            default -> throw new VBoxException("Unsupported USB controller: " + value);
        };
    }

    private static String audioControllerFromSoap(String value) {
        return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) {
            case "ac97" -> "ac97";
            case "hda" -> "hda";
            case "sb16" -> "sb16";
            default -> throw new IllegalArgumentException("Unsupported audio controller: " + value);
        };
    }

    private static String audioControllerToSoap(String value) throws VBoxException {
        return switch (value) {
            case "ac97" -> "AC97";
            case "hda" -> "HDA";
            case "sb16" -> "SB16";
            default -> throw new VBoxException("Unsupported audio controller: " + value);
        };
    }

    private static String audioDriverFromSoap(String value) {
        return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) {
            case "default" -> "default";
            case "none" -> "none";
            case "null" -> "null";
            case "directsound", "dsound" -> "directsound";
            case "was" -> "was";
            case "oss" -> "oss";
            case "alsa" -> "alsa";
            case "pulse" -> "pulse";
            case "coreaudio" -> "coreaudio";
            // WinMM, MMPM and SolAudio exist in the API but are not selectable here.
            default -> "default";
        };
    }

    private static String audioDriverToSoap(String value) throws VBoxException {
        return switch (value) {
            case "default" -> "Default";
            case "none" -> "None";
            case "null" -> "Null";
            case "directsound" -> "DirectSound";
            case "was" -> "WAS";
            case "oss" -> "OSS";
            case "alsa" -> "ALSA";
            case "pulse" -> "Pulse";
            case "coreaudio" -> "CoreAudio";
            default -> throw new VBoxException("Unsupported audio driver: " + value);
        };
    }

    /**
     * Maps NetworkAttachmentType onto the modes the settings editor offers. Modes
     * without an editor equivalent (Generic, NATNetwork, HostOnlyNetwork, Cloud)
     * are reported as disconnected so the rest of the dialog still opens.
     */
    private static String attachmentTypeFromSoap(String value) {
        return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) {
            case "nat" -> "nat";
            case "bridged" -> "bridged";
            case "hostonly" -> "hostonly";
            case "internal" -> "intnet";
            default -> "none";
        };
    }

    private static String attachmentTypeToSoap(String value) throws VBoxException {
        return switch (value) {
            case "nat" -> "NAT";
            case "bridged" -> "Bridged";
            case "hostonly" -> "HostOnly";
            case "intnet" -> "Internal";
            case "none" -> "Null";
            default -> throw new VBoxException("Unsupported network attachment type: " + value);
        };
    }

    private static String integrationModeFromSoap(String value) {
        return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) {
            case "disabled" -> "disabled";
            case "hosttoguest" -> "hosttoguest";
            case "guesttohost" -> "guesttohost";
            case "bidirectional" -> "bidirectional";
            default -> throw new IllegalArgumentException("Unsupported integration mode: " + value);
        };
    }

    private static String integrationModeToSoap(String value) throws VBoxException {
        return switch (value) {
            case "disabled" -> "Disabled";
            case "hosttoguest" -> "HostToGuest";
            case "guesttohost" -> "GuestToHost";
            case "bidirectional" -> "Bidirectional";
            default -> throw new VBoxException("Unsupported integration mode: " + value);
        };
    }

    private String callSingle(String operation, String arguments) throws VBoxException {
        List<String> response = callMany(operation, arguments);
        return response.isEmpty() ? "" : response.get(0);
    }

    private List<String> callMany(String operation, String arguments) throws VBoxException {
        return parseReturnValues(callResponse(operation, arguments));
    }

    /**
     * Reads results of interfaces the IDL marks {@code wsmap='struct'}, such as
     * IMediumAttachment and IVRDEServerInfo. The web service inlines their
     * attributes in the response instead of returning an object reference, so
     * there is no {@code IFoo_getBar} operation to call and nothing to release.
     */
    private List<Map<String, String>> callStructs(String operation, String arguments) throws VBoxException {
        try {
            Document document = parseDocument(callResponse(operation, arguments));
            throwIfFault(document);
            List<Map<String, String>> structs = new ArrayList<>();
            NodeList returnValues = document.getElementsByTagNameNS("*", "returnval");
            for (int index = 0; index < returnValues.getLength(); index++) {
                Map<String, String> fields = new HashMap<>();
                for (Element field : directElementChildren(returnValues.item(index))) {
                    String name = field.getLocalName() == null ? field.getNodeName() : field.getLocalName();
                    fields.putIfAbsent(name, field.getTextContent().trim());
                }
                structs.add(fields);
            }
            return structs;
        } catch (VBoxException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new VBoxException("Could not parse the VirtualBox web-service response.", exception);
        }
    }

    private static int optionalInt(String value, int fallback) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int parseField(String value, String label) throws VBoxException {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException exception) {
            throw new VBoxException("VirtualBox returned an invalid " + label + " value.", exception);
        }
    }

    /**
     * Reads several named output parameters from a single response. VirtualBox
     * maps the {@code [retval]} parameter to {@code returnval} and keeps the IDL
     * names for all remaining {@code out} parameters.
     */
    private Map<String, String> callFields(String operation, String arguments, String... fields)
            throws VBoxException {
        try {
            Document document = parseDocument(callResponse(operation, arguments));
            throwIfFault(document);
            Map<String, String> values = new HashMap<>();
            for (String field : fields) {
                NodeList matches = document.getElementsByTagNameNS("*", field);
                values.put(field, matches.getLength() == 0 ? "" : matches.item(0).getTextContent().trim());
            }
            return values;
        } catch (VBoxException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new VBoxException("Could not parse the VirtualBox web-service response.", exception);
        }
    }

    private String callResponse(String operation, String arguments) throws VBoxException {
        String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " xmlns:vbox=\"" + VBOX_NAMESPACE + "\">"
                + "<soapenv:Body><vbox:" + operation + ">" + arguments
                + "</vbox:" + operation + "></soapenv:Body></soapenv:Envelope>";

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("SOAPAction", "\"\"")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        long started = System.nanoTime();
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (LOG.isDebugEnabled()) {
                LOG.debug("{} took {} ms ({} bytes, HTTP {}).", operation,
                        (System.nanoTime() - started) / 1_000_000L,
                        response.body() == null ? 0 : response.body().length(), response.statusCode());
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                /*
                 * VirtualBox reports API errors as SOAP 1.1 faults, which arrive with
                 * HTTP 500. Let the caller's fault parsing produce the real message and
                 * only report the raw status when the body is not a usable fault.
                 */
                throw new VBoxException(faultMessage(response.body())
                        .orElseGet(() -> "VirtualBox web service returned HTTP " + response.statusCode() + ": "
                                + summarize(response.body())));
            }
            return response.body();
        } catch (IOException exception) {
            LOG.warn("{} could not reach {} after {} ms.", operation, endpoint,
                    (System.nanoTime() - started) / 1_000_000L, exception);
            throw new VBoxException("Could not reach the VirtualBox web service at " + endpoint + ".", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOG.warn("{} was interrupted.", operation);
            throw new VBoxException("VirtualBox web-service request was interrupted.", exception);
        }
    }

    private static String summarize(String body) {
        String text = (body == null ? "" : body).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return text.length() <= 500 ? text : text.substring(0, 500) + "…";
    }

    private static List<String> parseReturnValues(String xml) throws VBoxException {
        try {
            Document document = parseDocument(xml);

            throwIfFault(document);

            NodeList returnValues = document.getElementsByTagNameNS("*", "returnval");
            List<String> values = new ArrayList<>();
            for (int index = 0; index < returnValues.getLength(); index++) {
                Node returnValue = returnValues.item(index);
                List<Element> children = directElementChildren(returnValue);
                if (children.isEmpty()) {
                    values.add(returnValue.getTextContent());
                } else {
                    for (Element child : children) {
                        values.add(child.getTextContent());
                    }
                }
            }
            return values;
        } catch (VBoxException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new VBoxException("Could not parse the VirtualBox web-service response.", exception);
        }
    }

    private static List<Element> directElementChildren(Node parent) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node child = nodes.item(index);
            if (child instanceof Element element) {
                children.add(element);
            }
        }
        return children;
    }

    private static void throwIfFault(Document document) throws VBoxException {
        NodeList faults = document.getElementsByTagNameNS("*", "Fault");
        if (faults.getLength() > 0) {
            throw new VBoxException(describeFault(faults.item(0)));
        }
    }

    /**
     * Builds a readable message from a SOAP 1.1 or 1.2 fault. VirtualBox nests the
     * useful description in the fault detail rather than in {@code faultstring}.
     */
    private static String describeFault(Node fault) {
        List<String> parts = new ArrayList<>();
        for (String tag : List.of("faultstring", "Text", "text")) {
            NodeList matches = ((Element) fault).getElementsByTagNameNS("*", tag);
            for (int index = 0; index < matches.getLength(); index++) {
                String value = matches.item(index).getTextContent().trim();
                if (!value.isBlank() && !parts.contains(value)) {
                    parts.add(value);
                }
            }
        }
        if (parts.isEmpty()) {
            String text = fault.getTextContent().replaceAll("\\s+", " ").trim();
            return text.isBlank() ? "The VirtualBox web service reported an unspecified fault." : text;
        }
        return String.join(" — ", parts);
    }

    /**
     * Extracts the fault message from a raw response body, if it contains one.
     */
    private static Optional<String> faultMessage(String body) {
        if (body == null || !body.contains("Fault")) {
            return Optional.empty();
        }
        try {
            Document document = parseDocument(body);
            NodeList faults = document.getElementsByTagNameNS("*", "Fault");
            return faults.getLength() == 0 ? Optional.empty() : Optional.of(describeFault(faults.item(0)));
        } catch (Exception exception) {
            LOG.debug("A response mentioning a fault could not be parsed.", exception);
            return Optional.empty();
        }
    }

    private static Document parseDocument(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private int waitForDisplayPort(String console, String machineName) throws VBoxException {
        try {
            for (int attempt = 0; attempt < 5; attempt++) {
                List<Map<String, String>> info = callStructs("IConsole_getVRDEServerInfo",
                        element("_this", console));
                int port = info.isEmpty() ? -1 : optionalInt(info.get(0).get("port"), -1);
                if (port > 0) {
                    return port;
                }
                if (attempt < 4) {
                    Thread.sleep(1_000);
                }
            }
            throw new VBoxException("The remote display service for '" + machineName
                    + "' is not available yet.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new VBoxException("Interrupted while waiting for the remote display service of '"
                    + machineName + "'.", exception);
        }
    }

    private String launchDisplayClient(VirtualMachine machine, String host, int port, String extensionPack)
            throws VBoxException {
        RemoteBoxProfileReader.DisplaySettings settings = ApplicationSettings.shared().displaySettings();
        boolean vnc = extensionPack.toLowerCase(Locale.ROOT).contains("vnc");
        if (!vnc && settings.useMstsc()) {
            String command = start(mstscProcess(machine.name(), host, port, settings));
            if (settings.shareClipboard()) {
                MstscSecurityPrompt.confirmInBackground(host, SECURITY_PROMPT_TIMEOUT_SECONDS);
            }
            if (settings.autoScale()) {
                MstscZoom.applyInBackground(host, RdpConnectionFile.primaryScreenScalePercent(),
                        ZOOM_TIMEOUT_SECONDS);
            }
            return command;
        }
        String template = vnc ? settings.vncClient() : settings.rdpClient();

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("%h", host);
        placeholders.put("%p", Integer.toString(port));
        placeholders.put("%n", machine.name());
        placeholders.put("%o", machine.osType());
        placeholders.put("%U", username);
        placeholders.put("%P", new String(password));
        placeholders.put("%X", Integer.toString(settings.width()));
        placeholders.put("%Y", Integer.toString(settings.height()));
        placeholders.put("%D", Integer.toString(settings.depth()));

        /*
         * RDPCLIENT/VNCCLIENT is a complete command line (for example
         * "mstsc.exe /w:1800 /h:950 /v:%h:%p"). Split it into arguments *before*
         * substituting, and start the executable directly. Expanding the
         * placeholders into a shell command line would let a guest name or
         * password containing shell metacharacters run arbitrary commands.
         */
        List<String> command = new ArrayList<>();
        for (String token : VBoxManageClient.splitCommand(template)) {
            command.add(substitute(token, placeholders));
        }
        if (command.isEmpty()) {
            throw new VBoxException("No RemoteBox display client is configured.");
        }
        return start(new ProcessBuilder(command));
    }

    private static String start(ProcessBuilder process) throws VBoxException {
        List<String> command = process.command();
        try {
            process.start();
            return String.join(" ", command);
        } catch (IOException exception) {
            throw new VBoxException("Could not start the configured RemoteBox display client: "
                    + command.get(0), exception);
        }
    }

    private static ProcessBuilder mstscProcess(String machineName, String host, int port,
                                               RemoteBoxProfileReader.DisplaySettings settings)
            throws VBoxException {
        try {
            Path connectionFile = RdpConnectionFile.write(machineName, host, port, settings.width(),
                    settings.height(), settings.depth(), settings.shareClipboard());
            return new ProcessBuilder("mstsc.exe", connectionFile.toString());
        } catch (IOException | IllegalArgumentException exception) {
            throw new VBoxException("Could not write the temporary RDP connection file: "
                    + exception.getMessage(), exception);
        }
    }

    private static String substitute(String token, Map<String, String> placeholders) {
        String result = token;
        for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
            result = result.replace(placeholder.getKey(), placeholder.getValue());
        }
        return result;
    }

    /**
     * VirtualBox's SOAP RPC API places the operation in its namespace but leaves
     * operation parameters unqualified. This mirrors RemoteBox's SOAP::Lite
     * generated requests exactly.
     */
    private static String element(String name, String value) {
        return "<" + name + ">" + escapeXml(value) + "</" + name + ">";
    }

    private static String escapeXml(String value) {
        String ampersand = Character.toString((char) 38);
        return value.replace(ampersand, ampersand + "amp;")
                .replace("<", ampersand + "lt;")
                .replace(">", ampersand + "gt;")
                .replace("\"", ampersand + "quot;")
                .replace("'", ampersand + "apos;");
    }

    private static URI normalizeEndpoint(String endpoint) throws VBoxException {
        String value = endpoint == null ? "" : endpoint.trim();
        if (value.isBlank()) {
            throw new VBoxException("A VirtualBox web-service URL is required.");
        }
        if (!value.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            value = "http://" + value;
        }
        value = value.replaceAll("/+$", "");
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && !uri.getHost().isBlank()) {
                return uri;
            }
            throw new VBoxException("VirtualBox web-service URLs must use http or https and include a host: " + endpoint);
        } catch (IllegalArgumentException exception) {
            throw new VBoxException("Invalid VirtualBox web-service URL: " + endpoint, exception);
        }
    }
}
