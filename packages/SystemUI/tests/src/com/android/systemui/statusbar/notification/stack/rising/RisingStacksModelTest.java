package com.android.systemui.statusbar.notification.stack.rising;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;

public class RisingStacksModelTest {
    private final RisingStacksModel.AppKey personal =
            new RisingStacksModel.AppKey("example.chat", 0);
    private final RisingStacksModel.AppKey work =
            new RisingStacksModel.AppKey("example.chat", 10);
    private final RisingStacksModel.AppKey other =
            new RisingStacksModel.AppKey("example.mail", 0);

    private RisingStacksModel.Entry entry(String id, RisingStacksModel.AppKey app,
            String group, boolean summary, int rank) {
        return new RisingStacksModel.Entry(id, app, group, summary, rank);
    }

    @Test
    public void groupsSeparateProfilesAndSuppressSummaryWhenChildrenAreVisible() {
        RisingStacksModel.Snapshot snapshot = RisingStacksModel.build(Arrays.asList(
                entry("summary", personal, "group", true, 1),
                entry("child", personal, "group", false, 2),
                entry("child", personal, "group", false, 2),
                entry("work", work, null, false, 3),
                entry("mail", other, null, false, 0)), personal);
        assertEquals(3, snapshot.apps.size());
        assertEquals(other, snapshot.apps.get(0).app);
        assertEquals(personal, snapshot.apps.get(1).app);
        assertEquals(1, snapshot.apps.get(1).count);
        assertEquals(work, snapshot.apps.get(2).app);
        assertTrue(snapshot.includes(personal));
        assertFalse(snapshot.includes(work));
    }

    @Test
    public void summaryWithoutVisibleChildrenIsStillRepresented() {
        RisingStacksModel.Snapshot snapshot = RisingStacksModel.build(
                Collections.singletonList(entry("summary", personal, "group", true, 1)), null);
        assertEquals(1, snapshot.apps.get(0).count);
        assertTrue(snapshot.includes(other));
    }

    @Test
    public void removedSelectionRestoresAllNotifications() {
        RisingStacksModel.Snapshot snapshot = RisingStacksModel.build(
                Collections.singletonList(entry("mail", other, null, false, 1)), personal);
        assertNull(snapshot.selectedApp);
        assertTrue(snapshot.includes(other));
    }
}
