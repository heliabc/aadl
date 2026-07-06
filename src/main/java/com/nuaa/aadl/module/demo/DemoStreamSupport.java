package com.nuaa.aadl.module.demo;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

final class DemoStreamSupport {

    private DemoStreamSupport() {
    }

    static boolean streamText(DemoEventSender sender, SseEmitter emitter, AtomicBoolean streamClosed,
                              String eventName, String text) {
        if (text == null || text.isBlank()) return true;
        int cursor = 0;
        while (cursor < text.length()) {
            if (streamClosed.get()) return false;
            int next = nextChunkEnd(text, cursor, eventName);
            String chunk = text.substring(cursor, next);
            if (!sender.send(emitter, streamClosed, eventName, Map.of("text", chunk))) return false;
            cursor = next;
            try {
                Thread.sleep(nextDelayMs(chunk, eventName));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                streamClosed.set(true);
                return false;
            }
        }
        return true;
    }

    private static int nextChunkEnd(String text, int cursor, String eventName) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int remaining = text.length() - cursor;
        int min = "think".equals(eventName) ? 3 : 5;
        int max = "think".equals(eventName) ? 13 : 24;
        int target = Math.min(remaining, random.nextInt(min, max + 1));
        int hardEnd = cursor + target;

        int softEnd = findSoftBreak(text, cursor + min, hardEnd);
        return softEnd > cursor ? softEnd : hardEnd;
    }

    private static int findSoftBreak(String text, int from, int to) {
        int end = Math.min(to, text.length());
        for (int i = from; i < end; i++) {
            char ch = text.charAt(i);
            if (isSoftBreak(ch)) {
                return i + 1;
            }
        }
        return -1;
    }

    private static boolean isSoftBreak(char ch) {
        return ch == '，' || ch == '。' || ch == '；' || ch == '：'
                || ch == ',' || ch == '.' || ch == ';' || ch == ':'
                || ch == '\n';
    }

    private static long nextDelayMs(String chunk, String eventName) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long base = "think".equals(eventName)
                ? random.nextLong(75, 175)
                : random.nextLong(45, 125);
        if (chunk.endsWith("\n\n")) {
            return base + random.nextLong(220, 420);
        }
        if (chunk.endsWith("\n")) {
            return base + random.nextLong(120, 260);
        }
        char tail = chunk.charAt(chunk.length() - 1);
        if (tail == '。' || tail == '；' || tail == '.' || tail == ';') {
            return base + random.nextLong(120, 280);
        }
        if (tail == '，' || tail == '：' || tail == ',' || tail == ':') {
            return base + random.nextLong(50, 140);
        }
        return base;
    }

    @FunctionalInterface
    interface DemoEventSender {
        boolean send(SseEmitter emitter, AtomicBoolean streamClosed, String eventName, Object data);
    }
}
