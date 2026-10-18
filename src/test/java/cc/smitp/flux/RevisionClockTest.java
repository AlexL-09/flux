package cc.smitp.flux;

import cc.smitp.flux.runtime.RevisionClock;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RevisionClockTest {
    @Test void duplicateAndOldPacketsCannotConfirmClick() {
        var clock = new RevisionClock(63); assertTrue(clock.accept(27,10)); long issued = clock.sequence();
        assertFalse(clock.accept(27,10)); assertFalse(clock.accept(27,9)); assertEquals(issued,clock.revision(27));
        assertTrue(clock.accept(0,11)); assertEquals(issued,clock.revision(27));
        assertTrue(clock.accept(27,12)); assertTrue(clock.revision(27) > issued);
    }
    @Test void wraparoundIsNewButStaleWrappedPacketIsNot() {
        var clock = new RevisionClock(1); assertTrue(clock.accept(0,32767)); assertTrue(clock.accept(0,0));
        assertFalse(clock.accept(0,32766)); assertTrue(clock.accept(0,1));
        assertFalse(clock.accept(-1,2)); assertFalse(clock.accept(0,-1));
    }
}
