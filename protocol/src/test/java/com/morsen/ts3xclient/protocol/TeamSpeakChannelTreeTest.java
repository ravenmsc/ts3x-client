package com.morsen.ts3xclient.protocol;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static com.morsen.ts3xclient.protocol.TeamSpeakChannelTree.*;

public class TeamSpeakChannelTreeTest {
    private static List<String> entries(List<Entry> entries) {
        List<String> result = new ArrayList<>();
        for (Entry entry : entries) result.add(entry.id + ":" + entry.depth);
        return result;
    }
    @Test public void followsPredecessorsRatherThanNumericIdsAndHidesCollapsedDescendants() {
        List<Node> nodes = Arrays.asList(new Node(10, 0, 30, 1), new Node(30, 0, 0, 2),
                new Node(20, 10, 0, 3), new Node(40, 20, 0, 4), new Node(50, 900, 0, 5));
        assertEquals(Arrays.asList("30:0", "10:0", "20:1", "40:2", "50:0"),
                entries(flatten(nodes, Collections.emptySet())));
        assertEquals(Arrays.asList("30:0", "10:0", "50:0"),
                entries(flatten(nodes, Collections.singleton(10L))));
    }
    @Test public void handlesLargeFlatAndDeepTreesWithoutRecursiveStackGrowth() {
        int count = 20_000;
        List<Node> flat = new ArrayList<>(), deep = new ArrayList<>();
        for (int id = count; id > 0; id--) {
            flat.add(new Node(id, 0, id - 1, id));
            deep.add(new Node(id, id - 1, 0, id));
        }
        List<Entry> wide = flatten(flat, Collections.emptySet());
        List<Entry> nested = flatten(deep, Collections.emptySet());
        assertEquals(count, wide.size()); assertEquals(count, nested.size());
        for (int i = 0; i < count; i++) {
            assertEquals(i + 1, wide.get(i).id); assertEquals(0, wide.get(i).depth);
            assertEquals(i + 1, nested.get(i).id); assertEquals(i, nested.get(i).depth);
        }
        assertEquals(1, flatten(deep, Collections.singleton(1L)).size());
    }
    @Test public void incompleteChainsCyclesOrphansAndTiesKeepTheExistingStableOrder() {
        Random random = new Random(9738);
        for (int trial = 0; trial < 300; trial++) {
            List<Node> nodes = new ArrayList<>();
            Set<Long> collapsed = new HashSet<>();
            for (int id = 1; id <= 80; id++) {
                nodes.add(new Node(id, random.nextInt(12) - 1, random.nextInt(90), random.nextInt(8)));
                if (random.nextInt(12) == 0) collapsed.add((long) id);
            }
            Collections.shuffle(nodes, random);
            assertEquals("trial " + trial, legacy(nodes, collapsed), entries(flatten(nodes, collapsed)));
        }
    }
    /** Small-input reference for the old UI ordering, including transient cache gaps. */
    private static List<String> legacy(List<Node> nodes, Set<Long> collapsed) {
        Map<Long, List<Node>> grouped = new HashMap<>();
        for (Node node : nodes) grouped.computeIfAbsent(node.parentId, key -> new ArrayList<>()).add(node);
        Set<Long> hidden = new HashSet<>(), seen = new HashSet<>();
        Deque<Long> pending = new ArrayDeque<>(collapsed);
        while (!pending.isEmpty()) {
            for (Node child : grouped.getOrDefault(pending.removeLast(), Collections.emptyList()))
                if (hidden.add(child.id)) pending.addLast(child.id);
        }
        List<String> result = new ArrayList<>();
        walk(0, 0, grouped, collapsed, seen, result);
        List<Node> orphans = new ArrayList<>();
        for (Node node : nodes) if (!seen.contains(node.id) && !hidden.contains(node.id)) orphans.add(node);
        orphans.sort(Comparator.comparingLong(node -> node.firstSeen));
        for (Node node : orphans) result.add(node.id + ":0");
        return result;
    }
    private static void walk(long parent, int depth, Map<Long, List<Node>> grouped,
                             Set<Long> collapsed, Set<Long> seen, List<String> result) {
        Map<Long, Node> remaining = new LinkedHashMap<>();
        for (Node node : grouped.getOrDefault(parent, Collections.emptyList())) remaining.put(node.id, node);
        long predecessor = 0;
        while (!remaining.isEmpty()) {
            Node next = null;
            for (Node candidate : remaining.values())
                if (candidate.order == predecessor && (next == null || candidate.firstSeen < next.firstSeen)) next = candidate;
            if (next == null) {
                for (Node candidate : remaining.values()) {
                    if (next == null || (candidate.order == 0 && next.order != 0) ||
                            ((candidate.order == 0) == (next.order == 0) && candidate.firstSeen < next.firstSeen)) next = candidate;
                }
            }
            remaining.remove(next.id); predecessor = next.id;
            if (seen.add(next.id)) {
                result.add(next.id + ":" + depth);
                if (!collapsed.contains(next.id)) walk(next.id, depth + 1, grouped, collapsed, seen, result);
            }
        }
    }
}
