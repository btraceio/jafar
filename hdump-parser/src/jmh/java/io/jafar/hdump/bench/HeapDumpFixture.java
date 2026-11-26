package io.jafar.hdump.bench;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark fixtures: real HotSpot heap dumps of a deterministic, application-shaped object graph.
 *
 * <p>A fixture is named {@code <shape>-<scale>}; it is generated on first use by a child JVM that
 * builds the graph and dumps its own heap ({@code live=true}), and cached in {@code
 * hdump-parser/build/jmh-fixtures} (or {@code -Dhdump.bench.fixtures}). The graph is held only by a
 * local variable of the dumping thread, so it is reachable through a {@code ROOT_JAVA_FRAME} GC
 * root — not through static fields.
 *
 * <p>Shape {@code app}: {@code scale} entities in a {@code HashMap<String, Entity>}; each entity
 * owns a name, a small attribute map, a payload {@code byte[]}, a children list and a back
 * reference to its parent (cycles), and shares strings from a common pool (multi-inbound nodes).
 * Plus a {@code LinkedList} of {@code scale/2} nodes (deep chain), a binary tree of ~{@code scale}
 * nodes, a {@code ConcurrentHashMap<Integer, Object[]>} of shared entities and weak references.
 * Roughly 11 heap objects per unit of scale.
 *
 * <p>Shape {@code chain}: one singly linked list of {@code scale} nodes ({@link Link}), each
 * referenced once by its predecessor — the worst case of a per-object minimum-retained-size BFS.
 */
public final class HeapDumpFixture {

  private HeapDumpFixture() {}

  /** Returns the fixture file, generating it in a child JVM when absent. */
  public static Path ensure(String name) throws IOException, InterruptedException {
    Path dir =
        Path.of(System.getProperty("hdump.bench.fixtures", "hdump-parser/build/jmh-fixtures"))
            .toAbsolutePath();
    Files.createDirectories(dir);
    Path file = dir.resolve(name + ".hprof");
    if (Files.exists(file)) {
      return file;
    }
    String[] parts = name.split("-");
    if (parts.length != 2 || !(parts[0].equals("app") || parts[0].equals("chain"))) {
      throw new IllegalArgumentException(
          "unknown fixture: " + name + " (expected app-<scale> or chain-<length>)");
    }
    Path tmp = dir.resolve(name + ".tmp.hprof");
    Files.deleteIfExists(tmp);
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    Process p =
        new ProcessBuilder(
                java,
                "-Xmx6g",
                "-XX:+UseParallelGC",
                "-cp",
                System.getProperty("java.class.path"),
                HeapDumpFixture.class.getName(),
                parts[0],
                parts[1],
                tmp.toString())
            .inheritIO()
            .start();
    if (!p.waitFor(30, TimeUnit.MINUTES) || p.exitValue() != 0) {
      throw new IOException("fixture generation failed for " + name);
    }
    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
    return file;
  }

  // ---- the generated graph -------------------------------------------------------------------

  static final class Entity {
    final long id;
    final String name;
    final String category; // shared from pool
    final Map<String, Object> attrs = new HashMap<>(4);
    final byte[] payload;
    final List<Entity> children = new ArrayList<>(2);
    Entity parent;

    Entity(long id, String name, String category, byte[] payload) {
      this.id = id;
      this.name = name;
      this.category = category;
      this.payload = payload;
    }
  }

  static final class TreeNode {
    TreeNode left;
    TreeNode right;
    final int value;
    final Object tag;

    TreeNode(int value, Object tag) {
      this.value = value;
      this.tag = tag;
    }
  }

  static final class World {
    Map<String, Entity> entities;
    LinkedList<Long> chain;
    TreeNode tree;
    ConcurrentHashMap<Integer, Object[]> index;
    List<WeakReference<Entity>> weak;
    String[] pool;
  }

  static World build(int scale) {
    Random rnd = new Random(42);
    World w = new World();
    w.pool = new String[1024];
    for (int i = 0; i < w.pool.length; i++) {
      w.pool[i] = "category-" + i;
    }
    w.entities = new HashMap<>(scale * 2);
    Entity[] all = new Entity[scale];
    for (int i = 0; i < scale; i++) {
      Entity e =
          new Entity(
              i,
              "entity-" + i,
              w.pool[rnd.nextInt(w.pool.length)],
              new byte[16 + rnd.nextInt(240)]);
      e.attrs.put("k", w.pool[rnd.nextInt(w.pool.length)]);
      e.attrs.put("n", (long) rnd.nextInt(1_000_000));
      all[i] = e;
      w.entities.put(e.name, e);
      if (i > 0) {
        Entity parent = all[rnd.nextInt(i)];
        e.parent = parent;
        parent.children.add(e);
      }
    }
    w.chain = new LinkedList<>();
    for (int i = 0; i < scale / 2; i++) {
      w.chain.add((long) i * 7919);
    }
    w.tree = buildTree(0, scale, rnd, w.pool);
    w.index = new ConcurrentHashMap<>();
    for (int i = 0; i < scale / 8; i++) {
      Object[] bucket = new Object[3];
      for (int j = 0; j < bucket.length; j++) {
        bucket[j] = all[rnd.nextInt(scale)];
      }
      w.index.put(i, bucket);
    }
    w.weak = new ArrayList<>();
    for (int i = 0; i < scale / 16; i++) {
      w.weak.add(new WeakReference<>(all[rnd.nextInt(scale)]));
    }
    return w;
  }

  private static TreeNode buildTree(int lo, int hi, Random rnd, String[] pool) {
    if (lo >= hi) {
      return null;
    }
    int mid = (lo + hi) >>> 1;
    TreeNode n = new TreeNode(mid, (mid & 3) == 0 ? pool[rnd.nextInt(pool.length)] : null);
    n.left = buildTree(lo, mid, rnd, pool);
    n.right = buildTree(mid + 1, hi, rnd, pool);
    return n;
  }

  /** A singly linked list: every node is referenced once, by its predecessor. */
  static final class Link {
    final long value;
    Link next;

    Link(long value) {
      this.value = value;
    }
  }

  static Link buildChain(int length) {
    Link head = new Link(0);
    Link cur = head;
    for (int i = 1; i < length; i++) {
      cur.next = new Link(i);
      cur = cur.next;
    }
    return head;
  }

  public static void main(String[] args) throws Exception {
    String shape = args[0];
    int scale = Integer.parseInt(args[1]);
    Path out = Path.of(args[2]);
    Object world = shape.equals("chain") ? buildChain(scale) : build(scale);
    System.gc();
    HotSpotDiagnosticMXBean bean =
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
    bean.dumpHeap(out.toString(), true);
    Reference.reachabilityFence(world);
    System.out.println("fixture written: " + out + " (" + Files.size(out) + " bytes)");
  }
}
