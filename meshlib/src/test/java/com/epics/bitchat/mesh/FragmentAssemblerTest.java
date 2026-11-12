package com.epics.bitchat.mesh;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * FragmentAssembler unit tests: order-independent reassembly, tombstoned
 * echo copies (the flood re-delivers frames — the text must fire ONCE), and
 * the VISIBLE expiry of incomplete groups (a lost frame surfaces as a
 * dropped announcement, never as silence or garbled text).
 */
public class FragmentAssemblerTest {

    /** Records every callback for assertions. */
    private static final class Recorder implements FragmentAssembler.Callback {
        final List<String> assembled = new ArrayList<>();
        final List<String> expired = new ArrayList<>();

        @Override
        public void onTextAssembled(String sender, String groupId, MeshKind kind, String text) {
            assembled.add(text);
        }

        @Override
        public void onGroupExpired(String sender, String groupId, int received, int total) {
            expired.add(received + "/" + total);
        }
    }

    private static final String GROUP = "Q7X";
    private static final String SENDER = "AAA";

    private static String frame(int index, int total, String text) {
        return PacketCodec.makeFragmentFrame(GROUP, index, total, text);
    }

    @Test
    public void outOfOrderFramesAssembleExactlyOnce() {
        Recorder rec = new Recorder();
        FragmentAssembler asm = new FragmentAssembler(rec);

        asm.offer(SENDER, MeshKind.CHAT, frame(3, 3, "third"), 0L);
        asm.offer(SENDER, MeshKind.CHAT, frame(1, 3, "first "), 0L);
        assertNull("not complete yet", rec.assembled.isEmpty() ? null : "fired");
        assertEquals("no expiry while the group is open", 0, rec.expired.size());
        asm.offer(SENDER, MeshKind.CHAT, frame(2, 3, "second "), 0L);

        assertEquals(1, rec.assembled.size());
        assertEquals("first second third", rec.assembled.get(0));
        // The completed group stays as a tombstone (echo copies of the flood
        // frames must not re-fire the text) until the TTL sweep removes it.
        assertEquals(1, asm.pendingGroups());
    }

    @Test
    public void echoCopiesAfterCompletionAreAbsorbed() {
        Recorder rec = new Recorder();
        FragmentAssembler asm = new FragmentAssembler(rec);
        asm.offer(SENDER, MeshKind.CHAT, frame(1, 2, "hello "), 0L);
        asm.offer(SENDER, MeshKind.CHAT, frame(2, 2, "world"), 0L);
        assertEquals(1, rec.assembled.size());

        // The flood's own echoes arrive later: the text must NOT fire again.
        asm.offer(SENDER, MeshKind.CHAT, frame(1, 2, "hello "), 1_000L);
        asm.offer(SENDER, MeshKind.CHAT, frame(2, 2, "world"), 1_000L);
        assertEquals("tombstoned: echoes never re-fire the text", 1, rec.assembled.size());
        assertEquals(0, rec.expired.size());
    }

    @Test
    public void incompleteGroupExpiresVisibly() {
        Recorder rec = new Recorder();
        FragmentAssembler asm = new FragmentAssembler(rec);
        asm.offer(SENDER, MeshKind.CHAT, frame(1, 3, "lost "), 0L);
        asm.offer(SENDER, MeshKind.CHAT, frame(3, 3, "tail"), 100L);
        assertEquals(0, rec.assembled.size());

        // A later frame (another group) advances the clock past the TTL.
        long later = FragmentAssembler.GROUP_TTL_MS + 1_000L;
        asm.offer(SENDER, MeshKind.CHAT, frame(1, 2, "other"), later);

        assertEquals("incomplete group reported, not silently eaten",
                1, rec.expired.size());
        assertEquals("2/3 frames had arrived", "2/3", rec.expired.get(0));
        assertEquals("the lost text never fired", 0, rec.assembled.size());
    }

    @Test
    public void differentSendersNeverMixGroups() {
        Recorder rec = new Recorder();
        FragmentAssembler asm = new FragmentAssembler(rec);
        // Two senders happen to use the SAME group id (tiny id space): the
        // assembler keys by sender+group, so their texts must stay separate.
        asm.offer("AAA", MeshKind.CHAT, frame(1, 2, "alpha "), 0L);
        asm.offer("BBB", MeshKind.CHAT, frame(1, 2, "bravo "), 0L);
        asm.offer("AAA", MeshKind.CHAT, frame(2, 2, "one"), 0L);
        assertEquals(1, rec.assembled.size());
        assertEquals("alpha one", rec.assembled.get(0));
        asm.offer("BBB", MeshKind.CHAT, frame(2, 2, "two"), 0L);
        assertEquals(2, rec.assembled.size());
        assertEquals("bravo two", rec.assembled.get(1));
    }
}
