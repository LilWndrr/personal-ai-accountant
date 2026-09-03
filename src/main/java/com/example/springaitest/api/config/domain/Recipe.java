package com.example.springaitest.api.config.domain;

import java.util.List;

public record Recipe (String title,
                      List<String> ingredients,
                      List<String> instructions,
                      int prepTime,
                      int complexity){
}
