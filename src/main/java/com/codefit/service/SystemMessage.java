package com.codefit.service;

public record SystemMessage(String text, int priority) {
    public boolean hasText() {
        return text != null && !text.isBlank();
    }
}
