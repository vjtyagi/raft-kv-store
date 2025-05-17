package com.example.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;

public class EventLoop {
    private final LinkedBlockingQueue<Runnable> taskQueue = new LinkedBlockingQueue<>();
    private final Thread eventLoopThread;

    public EventLoop() {
        eventLoopThread = new Thread(this::processTasks);
        eventLoopThread.start();
    }

    private void processTasks() {
        while (true) {
            try {
                Runnable task = taskQueue.take();
                task.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void submit(Runnable task) {
        taskQueue.offer(task);
    }

    public <T> Future<T> submit(Callable<T> task) {
        FutureTask<T> future = new FutureTask<>(task);
        taskQueue.offer(future);
        return future;
    }

    public void shutdown() {
        eventLoopThread.interrupt();
    }
}
