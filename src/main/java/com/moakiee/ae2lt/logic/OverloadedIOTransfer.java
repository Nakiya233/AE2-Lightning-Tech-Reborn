package com.moakiee.ae2lt.logic;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;

/** One native storage batch. Work never scales with the number of items or buckets. */
public final class OverloadedIOTransfer {
    private OverloadedIOTransfer() {}

    public record Result(long inserted, long remainder, boolean powerBlocked) {}
    private static final Result NONE = new Result(0, 0, false);
    private static final Result NO_POWER = new Result(0, 0, true);

    public static Result move(MEStorage source, MEStorage destination, AEKey key,
                              IActionSource actionSource, java.util.function.BooleanSupplier pay) {
        long available = source.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, actionSource);
        if (available <= 0) return NONE;
        long accepted = destination.insert(key, available, Actionable.SIMULATE, actionSource);
        if (accepted <= 0) return NONE;
        if (!pay.getAsBoolean()) return NO_POWER;
        long extracted = source.extract(key, Math.min(available, accepted), Actionable.MODULATE, actionSource);
        if (extracted <= 0) return NONE;
        long inserted = destination.insert(key, extracted, Actionable.MODULATE, actionSource);
        long remainder = extracted - inserted;
        if (remainder > 0) remainder -= source.insert(key, remainder, Actionable.MODULATE, actionSource);
        return new Result(inserted, remainder, false);
    }
}
