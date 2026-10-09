package com.bookreader.core.engine

/**
 * 翻页动画的几何：位移和角度都是"进度 p(0→1) + 方向"的纯函数。
 *
 * 抽出来只为一件事：往左翻和往右翻必须严格互为镜像。这在没有测试之前是看不出来的——
 * 170ms 的动画截不到中间帧，而"新页永远从右边进场"这种错法在两张定稿截图里长得一模一样。
 */
object PageTurn {

    /** 离场页、进场页各自的水平位移，单位是"屏宽的比例"。dir=+1 往前翻、-1 往后翻。 */
    fun slideOffsets(p: Float, forward: Boolean): Pair<Float, Float> {
        val dir = if (forward) 1f else -1f
        val q = 1f - p
        return (dir * (q - 1f)) to (dir * q)
    }

    /**
     * 仿真翻折的抬起量（0=平放、1=转到侧棱）。两档都绕左边的书脊转：
     * 往前翻是把旧页掀起来，往后翻是把新页从左边铺回去，所以抬起量对调。
     */
    fun coverLift(p: Float, forward: Boolean): Float = if (forward) p else 1f - p

    /** 抬起量换算成绕 Y 轴的角度（负角 = 纸的右缘朝观察者立起来）。 */
    fun coverAngle(lift: Float): Float = -90f * lift
}
