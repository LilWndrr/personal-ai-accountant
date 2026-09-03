package com.example.springaitest.api.config.dto;

import java.util.List;

public record ValidationResult( boolean valid,
        List<Integer> invalidIndices) {

}
