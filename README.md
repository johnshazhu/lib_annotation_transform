# lib_annotation_transform
This plugin is for code stub in transform phase. It
needs to be used together with [lib_annotation](https://github.com/johnshazhu/lib_annotation).

check latest version of [lib_annotation_transform](https://plugins.gradle.org/plugin/io.github.johnshazhu.lib_annotation_transform)

```
plugins {
  id("io.github.johnshazhu.lib_annotation_transform") version "1.0.7"
}
```
You can view code in module test, it shows how to use @Inject annotation
inject method or field.

In our project, we may need inject some code to record log information. 
For example, we want record when activity was resumed. We can add log in
the method directly, but we do not want to do it with that way.
```
open class BaseActivity : Activity() {

}
```

Use this plugin and @Inject in [lib_annotation](https://github.com/johnshazhu/lib_annotation), 
 we can do it in the way as follows:
```
implementation("io.github.johnshazhu:lib_annotation:1.0.1")
```
Add @Inject annotation in the log method, the annotation info will be collected in transform phase, and will use
javassist to insert the code into target method. All the progress, the target class looks like nothing happened.
```
@Inject(target = BaseActivity::class, name = "onResume", before = true)
fun onResume() {
    Log.i("xdebug", "onResume")
}
```
Support custom config, we can add config info in transform_config.properties.
```
log_enable=true
save_log_enable=true
skip_package=org.greenrobot,com.networkbench,anet/channel,
skip_filename_prefix=R.,R$,kotlin,android,META-INF,com/lib/annotation
skip_filename_contain=BuildConfig,intellij,jetbrains,io.github.johnshazhu
```
