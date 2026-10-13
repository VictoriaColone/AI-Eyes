package com.aieyes.guide

import android.util.Log

/**
 * Maps a recognized Chinese (or English) utterance to a model class index.
 *
 * The bundled model is YOLOv8n trained on Open Images V7 with 601 classes.
 * CHINESE_ALIASES maps the exact English class name to the Chinese words users
 * are likely to say. Matching is done by "longest alias contained in the
 * utterance" after stripping spaces and punctuation, so "帮我找垃圾桶" resolves
 * to "Waste container".
 */
class CommandParser(private val labels: List<String>) {

    companion object {
        private const val TAG = "CommandParser"
    }

    private val indexByName: Map<String, Int> =
        labels.withIndex().associate { (i, n) -> n.lowercase() to i }

    private val chineseFirstByIdx: Map<Int, String>

    private val aliases: List<Pair<String, Int>>

    /** Fixed phrase vocabulary handed to the offline recognizer as a grammar. */
    val grammarPhrases: List<String>

    private val stopWords = listOf(
        "停止寻找", "不用找了", "不要找了", "不找了", "停止", "取消", "别找了", "不用了", "算了", "结束", "退出"
    )

    private val repeatWords = listOf(
        "再说一遍", "再念一遍", "重复一遍", "重复", "没有听到", "没听到", "再说一次"
    )

    private val confirmWords = listOf(
        "已经找到了", "找到了", "找到啦", "就是它", "就是这个", "摸到了", "摸到啦", "可以了"
    )

    init {
        val list = ArrayList<Pair<String, Int>>()
        val chineseFirst = HashMap<Int, String>()
        for ((english, chineseList) in chineseAliases()) {
            val idx = indexByName[english.lowercase()]
            if (idx == null) {
                Log.w(TAG, "Alias target not found in model labels: $english")
                continue
            }
            chineseFirst.putIfAbsent(idx, chineseList.first())
            for (word in chineseList) list.add(word.lowercase() to idx)
            list.add(english.lowercase() to idx)
        }
        // Allow English users to name any model class directly (skip short names
        // to avoid accidental substring matches).
        for ((i, name) in labels.withIndex()) {
            val n = name.lowercase()
            if (n.length >= 4) list.add(n to i)
        }
        aliases = list.sortedByDescending { it.first.length }
        chineseFirstByIdx = chineseFirst

        val phrases = LinkedHashSet<String>()
        for ((_, chineseList) in chineseAliases()) {
            for (word in chineseList) {
                phrases.add(word)
                phrases.add("帮我找$word")
                phrases.add("我要找$word")
                phrases.add("寻找$word")
                phrases.add("找$word")
            }
        }
        for (word in stopWords) {
            phrases.add(word)
            phrases.add("请$word")
        }
        for (word in repeatWords) phrases.add(word)
        for (word in confirmWords) phrases.add(word)
        phrases.add("找到了谢谢")
        phrases.add("摸到了谢谢")
        // Common fillers help the grammar-based decoder absorb extra words
        // ("帮我找一下垃圾桶") without failing the whole utterance.
        phrases.addAll(listOf("帮我", "我要", "请", "一下", "麻烦", "找", "寻找"))
        grammarPhrases = phrases.toList()
    }

    /** Chinese display name for a class index, or null if no alias is defined. */
    fun chineseName(classIndex: Int): String? = chineseFirstByIdx[classIndex]

    fun parse(raw: String): Command {
        val text = normalize(raw)
        if (text.isEmpty()) return Command.Unknown
        if (stopWords.any { text.contains(it) }) return Command.Stop
        if (repeatWords.any { text.contains(it) }) return Command.Repeat
        // Class aliases win over the confirm words, so a misheard "找个杯子"
        // ("找到了杯子") still starts a search instead of ending one.
        for ((alias, idx) in aliases) {
            if (text.contains(alias)) {
                return Command.FindTarget(idx, displayName(alias, idx))
            }
        }
        if (confirmWords.any { text.contains(it) }) return Command.Confirm
        return Command.Unknown
    }

    private fun displayName(alias: String, idx: Int): String {
        val isChinese = alias.any { it.code in 0x4E00..0x9FFF }
        return if (isChinese) alias else chineseFirstByIdx[idx] ?: labels[idx]
    }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[\\s，。！？、,.!?;；:：\"'“”‘’()（）\\[\\]【】]"), "")

    private fun chineseAliases(): Map<String, List<String>> = mapOf(
        "Person" to listOf("人", "有人", "行人", "一个人"),
        "Waste container" to listOf("垃圾桶", "垃圾箱", "垃圾筒", "果皮箱"),
        "Chair" to listOf("椅子", "凳子", "板凳", "座椅"),
        "Table" to listOf("桌子", "餐桌", "茶几", "台面"),
        "Desk" to listOf("书桌", "办公桌"),
        "Couch" to listOf("沙发"),
        "Bed" to listOf("床", "床铺"),
        "Toilet" to listOf("马桶", "厕所", "卫生间", "洗手间", "便池"),
        "Sink" to listOf("水槽", "洗手池", "水池", "洗碗池"),
        "Refrigerator" to listOf("冰箱", "冰柜"),
        "Microwave oven" to listOf("微波炉"),
        "Washing machine" to listOf("洗衣机"),
        "Dishwasher" to listOf("洗碗机"),
        "Gas stove" to listOf("灶台", "炉灶", "煤气灶"),
        "Bottle" to listOf("瓶子", "水瓶", "矿泉水"),
        "Coffee cup" to listOf("杯子", "水杯", "咖啡杯", "茶杯"),
        "Wine glass" to listOf("酒杯", "高脚杯"),
        "Mug" to listOf("马克杯"),
        "Bowl" to listOf("碗"),
        "Plate" to listOf("盘子", "碟子"),
        "Fork" to listOf("叉子", "餐叉"),
        "Knife" to listOf("餐刀"),
        "Kitchen knife" to listOf("菜刀", "厨刀", "刀"),
        "Spoon" to listOf("勺子", "汤匙", "调羹", "匙子"),
        "Scissors" to listOf("剪刀"),
        "Toothbrush" to listOf("牙刷"),
        "Hair dryer" to listOf("吹风机", "电吹风"),
        "Towel" to listOf("毛巾", "浴巾"),
        "Mirror" to listOf("镜子"),
        "Soap dispenser" to listOf("洗手液", "皂液器"),
        "Door" to listOf("门", "门口", "房门", "大门"),
        "Window" to listOf("窗户", "窗口"),
        "Stairs" to listOf("楼梯", "台阶"),
        "Light switch" to listOf("开关", "电灯开关"),
        "Lamp" to listOf("台灯"),
        "Street light" to listOf("路灯"),
        "Clock" to listOf("时钟", "挂钟", "钟"),
        "Alarm clock" to listOf("闹钟"),
        "Book" to listOf("书", "书本", "书籍"),
        "Pen" to listOf("钢笔", "笔"),
        "Laptop" to listOf("笔记本电脑", "笔记本", "电脑"),
        "Computer monitor" to listOf("显示器", "电脑屏幕", "屏幕"),
        "Computer keyboard" to listOf("键盘", "电脑键盘"),
        "Computer mouse" to listOf("鼠标"),
        "Television" to listOf("电视机", "电视"),
        "Remote control" to listOf("遥控器", "遥控"),
        "Mobile phone" to listOf("手机", "电话", "智能手机"),
        "Camera" to listOf("照相机", "相机"),
        "Backpack" to listOf("背包", "书包", "双肩包"),
        "Handbag" to listOf("手提包", "女包", "提包", "包包"),
        "Suitcase" to listOf("行李箱", "拉杆箱"),
        "Umbrella" to listOf("雨伞", "伞"),
        "Teddy bear" to listOf("泰迪熊", "毛绒熊", "毛绒玩具", "玩偶"),
        "Ball" to listOf("球", "皮球"),
        "Bicycle" to listOf("自行车", "单车", "脚踏车"),
        "Motorcycle" to listOf("摩托车", "摩托"),
        "Car" to listOf("汽车", "轿车", "小汽车"),
        "Bus" to listOf("公交车", "巴士", "大巴", "公共汽车"),
        "Truck" to listOf("卡车", "货车"),
        "Taxi" to listOf("出租车", "的士"),
        "Ambulance" to listOf("救护车"),
        "Traffic light" to listOf("红绿灯", "交通信号灯", "信号灯"),
        "Traffic sign" to listOf("交通标志", "路牌", "指示牌"),
        "Bench" to listOf("长椅", "长凳"),
        "Fire hydrant" to listOf("消防栓"),
        "Cabinetry" to listOf("柜子", "橱柜", "衣柜"),
        "Bathroom cabinet" to listOf("浴室柜"),
        "Filing cabinet" to listOf("文件柜"),
        "Drawer" to listOf("抽屉"),
        "Pillow" to listOf("枕头"),
        "Picture frame" to listOf("相框", "画框"),
        "Vase" to listOf("花瓶"),
        "Houseplant" to listOf("盆栽", "绿植", "植物"),
        "Flower" to listOf("鲜花", "花"),
        "Guitar" to listOf("吉他"),
        "Piano" to listOf("钢琴"),
        "Dumbbell" to listOf("哑铃"),
        "Kettle" to listOf("水壶", "烧水壶", "热水壶"),
        "Frying pan" to listOf("平底锅", "煎锅"),
        "Blender" to listOf("榨汁机", "搅拌机"),
        "Toaster" to listOf("烤面包机", "吐司机"),
        "Apple" to listOf("苹果"),
        "Banana" to listOf("香蕉"),
        "Orange" to listOf("橙子", "橘子"),
        "Watermelon" to listOf("西瓜"),
        "Strawberry" to listOf("草莓"),
        "Grape" to listOf("葡萄"),
        "Broccoli" to listOf("西兰花", "西蓝花"),
        "Carrot" to listOf("胡萝卜"),
        "Tomato" to listOf("西红柿", "番茄"),
        "Cucumber" to listOf("黄瓜"),
        "Cake" to listOf("蛋糕"),
        "Pizza" to listOf("披萨", "比萨"),
        "Hamburger" to listOf("汉堡包", "汉堡"),
        "Sandwich" to listOf("三明治"),
        "Cookie" to listOf("饼干"),
        "Bread" to listOf("面包"),
        "Egg (Food)" to listOf("鸡蛋"),
        "Milk" to listOf("牛奶"),
        "Cheese" to listOf("奶酪"),
        "Dog" to listOf("小狗", "狗"),
        "Cat" to listOf("小猫", "猫"),
        "Wheelchair" to listOf("轮椅"),
        "Crutch" to listOf("拐杖"),
        "Sunglasses" to listOf("墨镜", "太阳镜"),
        "Hat" to listOf("帽子"),
        "Footwear" to listOf("鞋子", "鞋"),
        "Boot" to listOf("靴子"),
        "Sandal" to listOf("凉鞋"),
        "Shelf" to listOf("搁板", "架子"),
        "Printer" to listOf("打印机"),
        "Teapot" to listOf("茶壶"),
        "Jug" to listOf("水罐"),
        "Picnic basket" to listOf("篮子", "筐"),
        "Box" to listOf("盒子"),
        "Barrel" to listOf("油桶", "木桶"),
        "Curtain" to listOf("窗帘"),
        "Shower" to listOf("淋浴"),
        "Bathtub" to listOf("浴缸"),
        "Light bulb" to listOf("灯泡"),
        "Flag" to listOf("旗子", "旗帜", "国旗"),
        "Facial tissue holder" to listOf("纸巾盒"),
        "Toilet paper" to listOf("卫生纸"),
        "Hand dryer" to listOf("干手机"),
        "Treadmill" to listOf("跑步机")
    )
}
