package com.repordar.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HeatmapPoint {

    private int day;
    private int hour;
    private int count;
}
