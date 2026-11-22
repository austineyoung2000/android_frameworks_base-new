package com.android.systemui.statusbar.notification.stack.rising;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds app groups from notifications that are already eligible for display on the lock screen.
 * This model only selects entries; it never dismisses or modifies notifications.
 */
public final class RisingStacksModel {
    private RisingStacksModel() {}

    /** Package and profile together identify an app; work and personal profiles stay separate. */
    public static final class AppKey {
        public final String packageName;
        public final int userId;

        public AppKey(String packageName, int userId) {
            this.packageName = Objects.requireNonNull(packageName);
            this.userId = userId;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof AppKey)) return false;
            AppKey key = (AppKey) other;
            return userId == key.userId && packageName.equals(key.packageName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(packageName, userId);
        }
    }

    public static final class Entry {
        public final String notificationKey;
        public final AppKey app;
        public final String groupKey;
        public final boolean groupSummary;
        public final int rank;

        public Entry(String notificationKey, AppKey app, String groupKey,
                boolean groupSummary, int rank) {
            this.notificationKey = Objects.requireNonNull(notificationKey);
            this.app = Objects.requireNonNull(app);
            this.groupKey = groupKey;
            this.groupSummary = groupSummary;
            this.rank = rank;
        }
    }

    public static final class AppGroup {
        public final AppKey app;
        public final int count;
        public final int highestRank;

        private AppGroup(AppKey app, int count, int highestRank) {
            this.app = app;
            this.count = count;
            this.highestRank = highestRank;
        }
    }

    public static final class Snapshot {
        public final List<AppGroup> apps;
        /** Null means show all; a selection missing from the new snapshot resets to all. */
        public final AppKey selectedApp;

        private Snapshot(List<AppGroup> apps, AppKey selectedApp) {
            this.apps = Collections.unmodifiableList(apps);
            this.selectedApp = selectedApp;
        }

        public boolean includes(AppKey app) {
            return selectedApp == null || selectedApp.equals(app);
        }
    }

    /**
     * Creates a snapshot from the lock-screen-visible entries only. The caller must apply all
     * existing lock-screen, profile, quiet-mode and notification visibility filters first.
     */
    public static Snapshot build(List<Entry> eligibleEntries, AppKey selectedApp) {
        Map<String, Entry> unique = new LinkedHashMap<>();
        for (Entry entry : eligibleEntries) {
            unique.putIfAbsent(entry.notificationKey, entry);
        }
        Set<GroupKey> groupsWithChildren = new HashSet<>();
        for (Entry entry : unique.values()) {
            if (!entry.groupSummary && entry.groupKey != null) {
                groupsWithChildren.add(new GroupKey(entry.app, entry.groupKey));
            }
        }
        Map<AppKey, MutableGroup> groups = new HashMap<>();
        for (Entry entry : unique.values()) {
            if (entry.groupSummary && entry.groupKey != null
                    && groupsWithChildren.contains(new GroupKey(entry.app, entry.groupKey))) {
                continue;
            }
            MutableGroup group = groups.computeIfAbsent(entry.app, ignored -> new MutableGroup());
            group.count++;
            group.highestRank = Math.min(group.highestRank, entry.rank);
        }
        List<AppGroup> apps = new ArrayList<>();
        for (Map.Entry<AppKey, MutableGroup> entry : groups.entrySet()) {
            apps.add(new AppGroup(entry.getKey(), entry.getValue().count,
                    entry.getValue().highestRank));
        }
        apps.sort(Comparator.comparingInt((AppGroup group) -> group.highestRank)
                .thenComparing(group -> group.app.packageName)
                .thenComparingInt(group -> group.app.userId));
        return new Snapshot(apps, groups.containsKey(selectedApp) ? selectedApp : null);
    }

    private static final class MutableGroup {
        int count;
        int highestRank = Integer.MAX_VALUE;
    }

    private static final class GroupKey {
        final AppKey app;
        final String group;

        GroupKey(AppKey app, String group) {
            this.app = app;
            this.group = group;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof GroupKey)) return false;
            GroupKey key = (GroupKey) other;
            return app.equals(key.app) && group.equals(key.group);
        }

        @Override
        public int hashCode() {
            return Objects.hash(app, group);
        }
    }
}
