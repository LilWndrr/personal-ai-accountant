package com.example.springaitest.dto;

import java.util.List;

public record ValidationResult( boolean valid,
        List<Integer> invalidIndices) {

}
