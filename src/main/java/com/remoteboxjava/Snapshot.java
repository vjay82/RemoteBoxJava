package com.remoteboxjava;

import java.util.List;

/**
 * A guest snapshot as shown in the Snapshots panel.
 *
 * @param timestampMillis creation time in milliseconds since the epoch, 0 when the transport cannot report it
 * @param online          whether the snapshot also captured the running machine state
 * @param current         whether the guest currently runs off this snapshot
 */
public record Snapshot(String id, String name, String description, long timestampMillis, boolean online,
                       boolean current, List<Snapshot> children) {

    public Snapshot {
        children = children == null ? List.of() : List.copyOf(children);
    }

    /** The identifier VirtualBox accepts for restore, delete, and clone operations. */
    public String reference() {
        return id == null || id.isBlank() ? name : id;
    }

    /** A guest's snapshot forest together with the state of its unsnapshotted current state. */
    public record Tree(List<Snapshot> roots, boolean currentStateModified) {
        public static final Tree EMPTY = new Tree(List.of(), false);

        public Tree {
            roots = roots == null ? List.of() : List.copyOf(roots);
        }

        public boolean isEmpty() {
            return roots.isEmpty();
        }
    }
}
