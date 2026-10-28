package com.epics.bitchat.mesh;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RssiFilterTest {

    @Test
    public void convergesOnStableSignal() {
        RssiFilter filter = new RssiFilter();
        int last = filter.update(-60);
        for (int i = 0; i < 50; i++) {
            last = filter.update(-60);
        }
        assertEquals(-60, last);
    }

    @Test
    public void smoothsTransientSpikes() {
        RssiFilter filter = new RssiFilter();
        int base = -60;
        for (int i = 0; i < 10; i++) {
            filter.update(base);
        }
        // a single strong spike should not yank the smoothed value all the way
        int result = filter.update(-30);
        assertTrue(result > -60);
        assertTrue(result < -30);
    }

    @Test
    public void resetClearsState() {
        RssiFilter filter = new RssiFilter();
        filter.update(-50);
        filter.reset();
        assertEquals(RssiFilter.NO_SIGNAL, filter.get());
    }
}
