package com.nuaa.aadl.module.rag.service;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

@Service
public class TextParserService {

    public String parseTextFile(File file) throws IOException {
        return Files.readString(file.toPath());
    }

    public List<String> parseTextFileLines(File file) throws IOException {
        return Files.readAllLines(file.toPath());
    }
}
