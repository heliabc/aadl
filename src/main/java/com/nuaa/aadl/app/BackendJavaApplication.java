package com.nuaa.aadl.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.nuaa.aadl")
public class BackendJavaApplication {

  public static void main(String[] args) {
    SpringApplication.run(BackendJavaApplication.class, args);
  }
}
