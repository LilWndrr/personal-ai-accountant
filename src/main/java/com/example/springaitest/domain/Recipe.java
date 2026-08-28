package com.example.springaitest.domain;

import java.util.List;

public record Recipe (String title,
                      List<String> ingredients,
                      List<String> instructions,
                      int prepTime,
                      int complexity){
}
