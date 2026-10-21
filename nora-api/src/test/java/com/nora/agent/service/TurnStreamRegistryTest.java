package com.nora.agent.service;

import org.junit.jupiter.api.Test;

class TurnStreamRegistryTest {
    @Test
    void contextMeterSurvivesLongReasoningReplaySmoke() {
        var registry = new TurnStreamRegistry();
        var turn = registry.start("context-smoke", "initial");
        turn.append("context_usage", "{\"promptTokens\":12000,\"contextWindow\":128000,\"roundIndex\":1}");
        for (int i = 0; i < 8_100; i++) turn.append("reasoning_delta", "{\"content\":\"thinking\"}");
        turn.snapshotAfter(0);
        turn.snapshotWithGap(0);
        turn.snapshotAfter(8_090);
        turn.append("context_usage", "{\"promptTokens\":18000,\"contextWindow\":128000,\"roundIndex\":2}");
        turn.snapshotAfter(0);
        turn.setSnapshotSupplier(() -> "{\"content\":\"\",\"steps\":[]}");
        turn.snapshotWithGap(0);
        turn.snapshotWithGap(2);
        turn.snapshotWithGap(8_090);
        turn.snapshotWithGap(100_000);
        var received = new java.util.ArrayList<TurnStreamRegistry.TurnEvent>();
        var subscription = turn.subscribeGated(0, received::add);
        turn.append("delta", "{\"content\":\"after snapshot\"}");
        received.addAll(subscription.snapshot().events());
        subscription.gate().open();
        turn.append("done", "{}");
        turn.unsubscribe(subscription.subscriber());
        turn.bufferedEvents();
        turn.lastEvent();
        registry.finish("context-smoke", turn);
    }

    @Test
    void steeringLifecycleSmoke() {
        var registry = new TurnStreamRegistry();
        var turn = registry.start("steering-smoke", "initial");
        var input = new TurnStreamRegistry.UserInput("input-1", "补充要求", null);
        var accepted = new java.util.ArrayList<TurnStreamRegistry.UserInput>();
        turn.enqueueInput(input, () -> {});
        turn.openInputs(accepted::add);
        turn.enqueueInput(input, () -> {});
        turn.enqueueInput(input, () -> {});
        turn.takeInputs(false);
        turn.enqueueInput(new TurnStreamRegistry.UserInput("input-2", "最终回答前补充", null), () -> {});
        turn.takeInputs(true);
        turn.takeInputs(true);
        turn.enqueueInput(input, () -> {});
        turn.openInputs(accepted::add);
        turn.enqueueInput(input, () -> {});
        registry.finish("steering-smoke", turn);
    }
}
