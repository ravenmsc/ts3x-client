package com.morsen.ts3xclient.protocol;

import java.util.*;

/** Stable CHANNEL_ORDER predecessor traversal without quadratic scans or recursion. */
public final class TeamSpeakChannelTree {
    private TeamSpeakChannelTree() { }

    public static final class Node {
        public final long id, parentId, order, firstSeen;
        public Node(long id, long parentId, long order, long firstSeen) {
            this.id = id; this.parentId = parentId; this.order = order; this.firstSeen = firstSeen;
        }
    }
    public static final class Entry {
        public final long id;
        public final int depth;
        Entry(long id, int depth) { this.id = id; this.depth = depth; }
    }

    public static List<Entry> flatten(List<Node> channels, Set<Long> collapsed) {
        Map<Long, List<Node>> grouped = new HashMap<>();
        for (Node node : channels) grouped.computeIfAbsent(node.parentId, key -> new ArrayList<>()).add(node);
        Set<Long> hidden = new HashSet<>();
        Deque<Long> pending = new ArrayDeque<>(collapsed);
        while (!pending.isEmpty()) {
            for (Node child : grouped.getOrDefault(pending.removeLast(), Collections.emptyList())) {
                if (hidden.add(child.id)) pending.addLast(child.id);
            }
        }
        Map<Long, List<Node>> ordered = new HashMap<>();
        for (Map.Entry<Long, List<Node>> group : grouped.entrySet())
            ordered.put(group.getKey(), siblings(group.getValue()));
        Set<Long> seen = new HashSet<>();
        List<Entry> result = new ArrayList<>(channels.size());
        Deque<Entry> stack = new ArrayDeque<>();
        pushChildren(stack, ordered.get(0L), 0);
        while (!stack.isEmpty()) {
            Entry entry = stack.removeLast();
            if (!seen.add(entry.id)) continue;
            result.add(entry);
            if (!collapsed.contains(entry.id)) pushChildren(stack, ordered.get(entry.id), entry.depth + 1);
        }
        // Preserve transient orphans while excluding deliberately collapsed descendants.
        List<Node> orphans = new ArrayList<>();
        for (Node node : channels) if (!seen.contains(node.id) && !hidden.contains(node.id)) orphans.add(node);
        orphans.sort(Comparator.comparingLong(node -> node.firstSeen));
        for (Node node : orphans) result.add(new Entry(node.id, 0));
        return result;
    }

    private static void pushChildren(Deque<Entry> stack, List<Node> children, int depth) {
        if (children == null) return;
        for (int index = children.size() - 1; index >= 0; index--)
            stack.addLast(new Entry(children.get(index).id, depth));
    }

    private static List<Node> siblings(List<Node> input) {
        if (input.size() <= 1) return input;
        Map<Long, Integer> positions = new HashMap<>();
        Map<Long, Node> remaining = new LinkedHashMap<>();
        for (Node node : input) {
            positions.putIfAbsent(node.id, positions.size());
            remaining.put(node.id, node);
        }
        Comparator<Node> arrival = Comparator.comparingLong((Node node) -> node.firstSeen)
                .thenComparingInt(node -> positions.get(node.id));
        List<Node> byArrival = new ArrayList<>(remaining.values());
        byArrival.sort(arrival);
        Map<Long, Deque<Node>> successors = new HashMap<>();
        for (Node node : byArrival)
            successors.computeIfAbsent(node.order, key -> new ArrayDeque<>()).addLast(node);
        PriorityQueue<Node> fallback = new PriorityQueue<>(Comparator
                .comparingInt((Node node) -> node.order == 0 ? 0 : 1).thenComparing(arrival));
        fallback.addAll(remaining.values());
        List<Node> result = new ArrayList<>(remaining.size());
        long predecessor = 0;
        while (!remaining.isEmpty()) {
            Deque<Node> candidates = successors.get(predecessor);
            while (candidates != null && !candidates.isEmpty() && !remaining.containsKey(candidates.peekFirst().id))
                candidates.removeFirst();
            Node next;
            if (candidates != null && !candidates.isEmpty()) next = candidates.removeFirst();
            else {
                while (!remaining.containsKey(fallback.peek().id)) fallback.remove();
                next = fallback.remove();
            }
            remaining.remove(next.id);
            result.add(next);
            predecessor = next.id;
        }
        return result;
    }
}
