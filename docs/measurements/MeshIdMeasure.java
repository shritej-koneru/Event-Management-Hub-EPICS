import com.epics.bitchat.mesh.*;

import java.security.SecureRandom;
import java.util.*;

/**
 * B5 measurement: mesh source-id derivation and message-id collision behaviour.
 * Uses the REAL meshlib classes (MeshMessage, PacketCodec, Router) @ offline-backend 7daad04.
 * The ONLY replicated logic is MeshManager.generateDeviceId (it lives in the Android :app module,
 * which is not on this classpath) - constants copied from MeshManager.java:59,61,243-250.
 *
 * Build/run (from merged/):
 *   javac -cp meshlib/build/classes/java/main -d %TEMP%/mim docs/measurements/MeshIdMeasure.java
 *   java  -cp meshlib/build/classes/java/main;%TEMP%/mim MeshIdMeasure
 * Output is deterministic in structure; Monte-Carlo figures use a fixed seed where noted.
 */
public class MeshIdMeasure {
    static final String ALPHA = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // MeshManager.DEVICE_ID_ALPHABET
    static final int LEN = 3;                                        // MeshManager.DEVICE_ID_LEN

    static String genDeviceId(Random r) {
        StringBuilder sb = new StringBuilder(LEN);
        for (int i = 0; i < LEN; i++) sb.append(ALPHA.charAt(r.nextInt(ALPHA.length())));
        return sb.toString();
    }

    public static void main(String[] a) {
        partA();
        partB();
        partC();
        partD();
    }

    // ---- A: source (device) id ------------------------------------------------------------
    static void partA() {
        System.out.println("== A. Source device id (3 chars of a 32-char alphabet, SecureRandom, persisted)");
        double space = Math.pow(ALPHA.length(), LEN);
        System.out.printf("keyspace = %d^%d = %.0f%n", ALPHA.length(), LEN, space);
        System.out.printf("P(one given pair of installs share an id) = 1/%.0f = %.6f%%%n", space, 100 / space);
        System.out.println("installs | exact P(any two share an id) | Monte Carlo (20000 trials, seed 42)");
        Random mc = new Random(42);
        for (int n : new int[]{5, 10, 30, 50, 100, 200, 500, 1000}) {
            double pNo = 1;
            for (int i = 0; i < n; i++) pNo *= (space - i) / space;
            int hit = 0, trials = 20000;
            for (int t = 0; t < trials; t++) {
                Set<String> s = new HashSet<>();
                for (int i = 0; i < n; i++) if (!s.add(genDeviceId(mc))) { hit++; break; }
            }
            System.out.printf("%8d | %27.4f%% | %.4f%%%n", n, 100 * (1 - pNo), 100.0 * hit / trials);
        }
        // Stored id is only regenerated when prefs lack a 3-char id: reinstall/clear-data => new id.
        System.out.println("note: id persists in SharedPreferences; app data clear / reinstall => new random id (source reading).");
        System.out.println();
    }

    // ---- B: wire message id ------------------------------------------------------------------
    static void partB() {
        System.out.println("== B. Wire message id (first 3 ASCII chars of a random UUID string)");
        int N = 2_000_000;
        Set<String> wireIds = new HashSet<>();
        Set<Character> chars = new TreeSet<>();
        int lenMismatch = 0;
        for (int i = 0; i < N; i++) {
            MeshMessage m = MeshMessage.create("ABC", "", MeshKind.CHAT, "hi");
            MeshMessage d = PacketCodec.decode(PacketCodec.encode(m));
            String id = d.getMsgId();
            if (id.length() != 3) lenMismatch++;
            wireIds.add(id);
            for (char c : id.toCharArray()) chars.add(c);
        }
        System.out.printf("messages created+encoded+decoded: %d%n", N);
        System.out.printf("distinct wire msgIds observed   : %d  (theory 16^3 = 4096)%n", wireIds.size());
        System.out.printf("alphabet observed               : %s%n", chars);
        System.out.printf("ids not exactly 3 chars         : %d%n", lenMismatch);
        System.out.printf("effective entropy               : %.1f bits%n", Math.log(wireIds.size()) / Math.log(2));
        // Sender keeps the FULL uuid locally; receivers only ever see the 3-char prefix.
        MeshMessage local = MeshMessage.create("ABC", "", MeshKind.CHAT, "hi");
        MeshMessage remote = PacketCodec.decode(PacketCodec.encode(local));
        System.out.printf("sender-side id length %d vs receiver-side id length %d (same message)%n",
                local.getMsgId().length(), remote.getMsgId().length());
        System.out.println();
    }

    // ---- C: Router dedup uses msgId ALONE with a 4096 LRU --------------------------------------
    static void partC() {
        System.out.println("== C. Router dedup (key = msgId only, maxSeen = 4096, never reset in app/meshlib)");
        System.out.println("fresh Router, N distinct messages from DIFFERENT senders heard in sequence;");
        System.out.println("'delivered' = reached the sink. Average of 100 runs.");
        System.out.println("   N heard | delivered (avg) | % delivered | theory 4096*(1-e^(-N/4096))");
        for (int n : new int[]{100, 500, 1000, 2000, 4096, 8192, 20000, 50000}) {
            long total = 0;
            int runs = 100;
            for (int run = 0; run < runs; run++) {
                final int[] delivered = {0};
                Router r = new Router("ME1", new MessageLog(10), new Router.Sink() {
                    public void onDelivered(MeshMessage m, int rssi) { delivered[0]++; }
                    public void onRelay(MeshMessage m, int rssi) { }
                });
                for (int i = 0; i < n; i++) {
                    String src = ALPHA.substring(i % 29, i % 29 + 3);
                    MeshMessage m = PacketCodec.decode(PacketCodec.encode(
                            MeshMessage.create(src, "", MeshKind.CHAT, "x")));
                    r.onReceive(m, -60, src);
                }
                total += delivered[0];
            }
            double avg = total / 100.0;
            System.out.printf("%9d | %15.1f | %10.1f%% | %.1f%n", n, avg, 100 * avg / n,
                    4096 * (1 - Math.exp(-n / 4096.0)));
        }
        // Same message heard twice must still dedup (correct behaviour preserved).
        final int[] d = {0};
        Router r = new Router("ME1", new MessageLog(10), new Router.Sink() {
            public void onDelivered(MeshMessage m, int rssi) { d[0]++; }
            public void onRelay(MeshMessage m, int rssi) { }
        });
        MeshMessage m = PacketCodec.decode(PacketCodec.encode(MeshMessage.create("ABC", "", MeshKind.CHAT, "x")));
        r.onReceive(m, -60, "ABC");
        r.onReceive(m, -60, "XYZ");
        System.out.printf("sanity: same message heard twice => delivered %d time(s) (expected 1)%n", d[0]);
        System.out.println();
    }

    // ---- D: properties a sync clientId could rely on ---------------------------------------------
    static void partD() {
        System.out.println("== D. Can two gateways derive the same id for the same heard message?");
        MeshMessage orig = MeshMessage.create("ABC", "", MeshKind.CHAT, "hello");
        byte[] wire = PacketCodec.encode(orig);
        MeshMessage g1 = PacketCodec.decode(wire);
        try { Thread.sleep(1500); } catch (InterruptedException e) { }
        MeshMessage g2 = PacketCodec.decode(wire);
        System.out.printf("gateway1 createdAt=%d  gateway2 createdAt=%d  (decode stamps LOCAL receive time)%n",
                g1.getCreatedAtEpochMs(), g2.getCreatedAtEpochMs());
        System.out.printf("original createdAt=%d  (never on the wire)%n", orig.getCreatedAtEpochMs());
        System.out.printf("same src/msgId on both gateways: %s / %s%n",
                g1.getSrcId().equals(g2.getSrcId()), g1.getMsgId().equals(g2.getMsgId()));
        long s1 = g1.getCreatedAtEpochMs() / 1000, s2 = g2.getCreatedAtEpochMs() / 1000;
        System.out.printf("placeholder 'm1:src:msgId:createdAtSec' agrees across gateways: %s%n",
                s1 == s2);

        // Probability that, within one sender and one second, two distinct messages share a wire id.
        System.out.println("pairs of distinct messages from the SAME sender in the SAME second share a wire id with p = 1/4096");
        // Fragment group id: how it is carried and its space.
        String g = PacketCodec.fragmentGroupId("ABC", "a long message that needs fragmenting", 1L);
        System.out.printf("fragment group id sample=%s (3 chars of 32-char alphabet => 32768 values; FNV-1a over src|text|epochMs)%n", g);
        List<String> frames = PacketCodec.fragment("this is a long chat message that will be split into frames", g);
        System.out.printf("a 59-char text becomes %d frames; each frame is its own MeshMessage with its OWN random 3-hex msgId%n",
                frames.size());
        Set<String> frameIds = new HashSet<>();
        for (String f : frames) {
            MeshMessage fm = PacketCodec.decode(PacketCodec.encode(MeshMessage.create("ABC", "", MeshKind.CHAT, f)));
            frameIds.add(fm.getMsgId());
        }
        System.out.printf("distinct msgIds across those frames: %d (so frame ids are not a message identity)%n", frameIds.size());
    }
}
