# Room 实体由反射生成的实现访问，字段名必须保留。
-keepattributes *Annotation*
-keep class com.guanglu.caliper.core.database.entity.** { *; }
-keep class com.guanglu.caliper.core.model.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { <init>(...); }
-keep class * extends androidx.room.RoomDatabase { <init>(...); }

# blessed-android 的回调由蓝牙栈反射/异步调用，保留全部类型。
-keep class com.welie.blessed.** { *; }
-keep class com.guanglu.caliper.core.bluetooth.** { *; }

# CSV 导入按稳定大写列名映射，避免列名被混淆后读不出数据。
-keepclassmembers class com.guanglu.caliper.core.usecase.CsvCodec { *; }
