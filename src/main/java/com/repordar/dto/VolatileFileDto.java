package com.repordar.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VolatileFileDto {

    private String path;
    private int changeCount;
    private int windowDays;
    private List<String> contributors;
    private int linesChanged;
}
