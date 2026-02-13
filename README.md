#C++模块 match server，撮合模块

*不要将本branch的REAMDME合并到其它branch的README中去*

*建议采用ssh方式连接远程，避免网络不稳定无法连接github*

**当前分支由空分支创建得来，未来接口和环境与其余分支对上时可直接合并**

环境配置：（待定）

一个解析json文件的库（待定），cmake，

**本分支matcher-server下有一个build文件夹未跟踪，需要自行建立**

各文件夹功能

trading-simulator/

    mathcer-server/

        benchmarks *性能测试*

        build *项目构建*

        include/ *存放头文件*

            model *定义与 Java 端对应的 POD (Plain Old Data) 结构体*

            core *声明撮合逻辑的核心类，如 OrderBook 和 Matcher*

            ipc *定义进程间通信的接口*

            util *存放高精度计时器、无锁队列 (Lock-free Queue) 或日志工具*

        src *源代码*

        tests *功能测试*

        third_party *外部依赖库，比如解析存储json的库*
