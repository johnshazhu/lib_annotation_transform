# lib_annotation_transform
This plugin is for code stub in transform phase. It
needs to be used together with [lib_annotation](https://github.com/johnshazhu/lib_annotation).

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
implementation("io.github.johnshazhu:lib_annotation:1.0.0")
```
Add @Inject annotation in the log method, the annotation info will be collected in transform phase, and will use
javassist to insert the code into target method. All the progress, the target class looks like nothing happened.
```
@Inject(target = BaseActivity::class, name = "onResume", before = true)
fun onResume() {
    Log.i("xdebug", "onResume")
}
```
