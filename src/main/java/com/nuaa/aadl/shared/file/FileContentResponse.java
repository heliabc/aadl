package com.nuaa.aadl.shared.file;

public record FileContentResponse(
    String id,
    String name,
    String mime,
    String content
) {}
