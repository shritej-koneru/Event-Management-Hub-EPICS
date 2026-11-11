import java.util.*;

/**
 * Compares Router dedup-key policies under the same simulated traffic. PROTOTYPE ONLY: it does not
 * touch meshlib. Wire ids are 12-bit (3 hex chars) exactly as PacketCodec produces them; source ids
 * are drawn from the real 32^3 device-id space (so twin sources occur naturally).
 *
 * Traffic per phone: a venue beacon every 10 s (constant payload, like the real V:<venue> advert),
 * plus chat at ~1/min drawn from a tiny phrase set (so identical repeats really happen).
 * Every original is heard 3 times (original + 2 relayed echoes 0.5 s / 1.2 s later), like a flood.
 *
 *   FALSE DROP = a NEW message the receiver wrongly discards   (message loss)
 *   FALSE NEW  = an ECHO the receiver wrongly delivers again   (duplicate / loop risk)
 *
 * Run: javac DedupPolicySim.java && java DedupPolicySim   (fixed seed => reproducible)
 */
public class DedupPolicySim {
    static final String ALPHA = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final String[] PHRASES = {"ok", "yes", "no", "hi", "where?", "coming", "thanks", "help", "on my way", "ready"};
    static final String[] VENUES = {"V:Hall 1", "V:Hall 2", "V:Library", "V:Cafeteria", "V:Admin Block"};

    static final class Ev {
        final double t; final String src, id, payload; final boolean echo;
        Ev(double t, String s, String i, String p, boolean e) { this.t = t; src = s; id = i; payload = p; echo = e; }
    }

    interface Policy { String key(Ev e); long ttlMs(); int cap(); String name(); }

    static Policy p(String n, long ttl, java.util.function.Function<Ev, String> k) {
        return new Policy() {
            public String key(Ev e) { return k.apply(e); }
            public long ttlMs() { return ttl; }
            public int cap() { return 4096; }
            public String name() { return n; }
        };
    }

    public static void main(String[] a) {
        Policy[] pol = {
            p("P0 current: id only, LRU 4096, no expiry", Long.MAX_VALUE, e -> e.id),
            p("P1 id only, 120 s expiry", 120_000, e -> e.id),
            p("P2 (src,id), 120 s expiry", 120_000, e -> e.src + "|" + e.id),
            p("P3 (src,id,payload), 120 s expiry", 120_000, e -> e.src + "|" + e.id + "|" + e.payload),
        };
        System.out.println("1 simulated hour, one receiver hearing everything. Seed fixed.");
        for (int phones : new int[]{10, 30, 100, 300}) {
            Random r = new Random(1234 + phones);
            List<Ev> evs = new ArrayList<>();
            int originals = 0;
            for (int i = 0; i < phones; i++) {
                String src = "" + ALPHA.charAt(r.nextInt(32)) + ALPHA.charAt(r.nextInt(32)) + ALPHA.charAt(r.nextInt(32));
                String venue = VENUES[r.nextInt(VENUES.length)];
                for (double t = r.nextDouble() * 10; t < 3600; t += 10) { originals++; add(evs, t, src, venue, r); }
                for (double t = r.nextDouble() * 60; t < 3600; t += -60 * Math.log(1 - r.nextDouble())) {
                    originals++; add(evs, t, src, PHRASES[r.nextInt(PHRASES.length)], r);
                }
            }
            evs.sort(Comparator.comparingDouble(x -> x.t));
            System.out.printf("%n== %d phones: %d originals (%.1f/s), %d frames heard%n", phones, originals, originals / 3600.0, evs.size());
            System.out.println("policy                                         | false drops (of originals) | false NEW (of echoes)");
            for (Policy po : pol) {
                LinkedHashMap<String, Double> seen = new LinkedHashMap<>(16, 0.75f, true);
                int fd = 0, fn = 0, echoes = 0;
                for (Ev e : evs) {
                    String k = po.key(e);
                    Double at = seen.get(k);
                    boolean known = at != null && (e.t * 1000 - at * 1000) <= po.ttlMs();
                    if (e.echo) { echoes++; if (!known) fn++; }
                    else if (known) fd++;
                    // sliding expiry: EVERY sighting (first or repeat) refreshes the entry. Without this
                    // (first version of this sim) echoes of a late duplicate crossed the expiry and were
                    // delivered a second time - a real design constraint, kept here as a comment.
                    seen.put(k, e.t);
                    if (seen.size() > po.cap()) { Iterator<String> it = seen.keySet().iterator(); it.next(); it.remove(); }
                }
                System.out.printf("%-46s | %7d (%5.2f%%)         | %6d (%5.2f%%)%n", po.name(), fd, 100.0 * fd / originals, fn, 100.0 * fn / echoes);
            }
        }
    }

    static void add(List<Ev> evs, double t, String src, String payload, Random r) {
        String id = String.format("%03x", r.nextInt(4096));
        evs.add(new Ev(t, src, id, payload, false));
        evs.add(new Ev(t + 0.5, src, id, payload, true));
        evs.add(new Ev(t + 1.2, src, id, payload, true));
    }
}
