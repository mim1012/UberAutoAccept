package com.uber.autoaccept;

interface IShizukuService {
    void destroy() = 16777114;
    void exit() = 16777113;
    boolean tap(int x, int y) = 1;
    boolean tapRepeat(int x, int y, int times, int intervalMs) = 2;
}
