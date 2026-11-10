package xdviofdmx.model;

/**
 * 命名目标点（pdf:dest）：页面空间坐标（毫米）。
 */
public record DestPoint(String name, int pageIndex, double xMm, double yMm) {}
