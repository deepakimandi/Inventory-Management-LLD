package com.lld.inventory.events;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * Subject of the Observer pattern.
 *  - CopyOnWriteArrayList: listeners are added rarely and iterated on every event, safe without locks.
 *  - Executor: Runnable::run for synchronous delivery (tests), a thread pool for async (slow e-mail/SMS).
 *  - One failing listener never breaks the stock operation or the other listeners.
 * The service calls publish() only AFTER releasing the stock lock: never run foreign code while holding a lock.
 */
public final class StockEventPublisher {
    private final List<StockEventListener> listeners = new CopyOnWriteArrayList<>();
    private final Executor executor;

    public StockEventPublisher(Executor executor) { this.executor = executor; }

    public void subscribe(StockEventListener l) { listeners.add(l); }
    public void unsubscribe(StockEventListener l) { listeners.remove(l); }

    public void publish(StockEvent event) {
        for (StockEventListener l : listeners) {
            executor.execute(() -> {
                try {
                    l.onEvent(event);
                } catch (RuntimeException e) {
                    System.err.println("[events] listener failed on " + event.type() + ": " + e.getMessage());
                }
            });
        }
    }
}
