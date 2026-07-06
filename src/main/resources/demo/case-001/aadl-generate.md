# AADL Generate Demo Case

<!-- STEP1_THINK -->
好的，我现在需要根据用户提供的电爆管系统自然语言需求，生成一套标准的系统架构模板。我会按照常规的分区架构思路，逐项推理。

首先，系统名称是“电爆管系统”，我会在模板顶部写上这个名称。

1. 系统管理器与系统模块

任何安全关键的嵌入式系统通常都有一个管理器负责整体调度、健康监控和模式管理，还有一个模块来承载具体功能分区。即使需求没有明确提及，架构模板中一般都会包含这两项。所以我先写上“电爆管系统管理器”和“电爆管系统模块”。

2. 模块内部结构

需求中明确给出了五路控制信号：

姿态火箭点火控制信号、降落伞弹射枪点火控制信号、偏导器点火控制信号、火箭包点火控制信号、燃烧与爆炸分离机制点火控制信号。

每路信号对应一台电爆管设备（ED1~ED5），并且需要独立执行点火动作。在分区架构中，每路独立的控制功能应该放在一个独立的分区里，每个分区内有一个任务来执行具体的点火逻辑。因此我创建五个分区和对应的任务。

关于命名：需求中“降落伞弹射枪”在工程实践中常被简称为“降落伞发射器”，我采用更标准的“降落伞发射器”作为分区名称。其他分区直接采用需求中的核心名词。

于是：
(2) 姿态火箭点火分区：姿态火箭点火任务
(3) 降落伞发射器点火分区：降落伞发射器点火任务
(4) 偏导器点火分区：偏导器点火任务
(5) 火箭包点火分区：火箭包点火任务
(6) 燃烧爆炸分离分区：燃烧爆炸分离任务

网络接口。需求中提到“依托 cn1、cn2、cn3、cn4、cn5 五路连接链路分别对接五路外部输入输出事件信号”。这意味着系统需要一个统一的网络接口来处理这些链路的信号收发、路由和协议转换。因此增加：
(7) 电爆管网络接口

处理器与存储器。架构模板通常要求列出所有分区和网络接口在处理器上的运行分配以及在存储器中的内存分配。由于需求没有指定具体硬件分区策略，我按常规做法：将所有六个组件（五个分区加网络接口）都列出来，分别加上“运行”和“内存”后缀。

设备。需求明确给出“ED1、ED2、ED3、ED4、ED5 五台电爆管设备”，直接列出：设备：电爆管设备1、电爆管设备2、电爆管设备3、电爆管设备4、电爆管设备5。

总线。需求中有 cn1~cn5 五路连接链路。我需要判断这些是否属于“总线”。在系统架构中，“总线”通常指多个设备共享的通信介质，而这里 cn1~cn5 是五条独立的点对点连接链路，更像专用信号线或硬线连接。因此，这个系统中不存在共享总线。

数据。五路控制信号名称直接取自需求，采用中文：姿态火箭点火控制信号、降落伞发射器点火控制信号、偏导器点火控制信号、火箭包点火控制信号、燃烧爆炸分离点火控制信号。

程序。架构模板通常将程序分为三类：模块与设备的初始化、运行、终止；分区与网络接口的冷启动、热启动、正常、空闲；任务对应的具体业务程序。

性能指标。需求末尾的“周期1ms、截止时间1ms、单次计算执行时间1~2ms”属于实时性约束，在高层架构模板中没有对应的填写位置。这些值会在后续细化设计时作为线程或分区的属性添加，不在当前模板中体现。

最终，我按照上述推理整理出完整的模板，输出如下：

<!-- STEP1_TEMPLATE -->
电爆管系统模块

(1) 电爆管系统模块管理器（即电爆管系统管理器）
(2) 姿态火箭点火分区：姿态火箭点火任务
(3) 降落伞发射器点火分区：降落伞发射器点火任务
(4) 偏导器点火分区：偏导器点火任务
(5) 火箭包点火分区：火箭包点火任务
(6) 燃烧爆炸分离分区：燃烧爆炸分离任务
(7) 电爆管网络接口
(8) 处理器：姿态火箭点火分区运行、降落伞发射器点火分区运行、偏导器点火分区运行、火箭包点火分区运行、燃烧爆炸分离分区运行、电爆管网络接口运行
(9) 存储器：姿态火箭点火分区内存、降落伞发射器点火分区内存、偏导器点火分区内存、火箭包点火分区内存、燃烧爆炸分离分区内存、电爆管网络接口内存

设备：电爆管设备1、电爆管设备2、电爆管设备3、电爆管设备4、电爆管设备5

总线：0个总线（模型中未定义总线）

数据：姿态火箭点火控制信号、降落伞发射器点火控制信号、偏导器点火控制信号、火箭包点火控制信号、燃烧爆炸分离点火控制信号

程序
(1) 模块与设备：初始化程序、运行程序、终止程序
(2) 分区与网络接口：冷启动程序、热启动程序、正常程序、空闲程序
(3) 任务：姿态火箭点火程序、降落伞发射器点火程序、偏导器点火程序、火箭包点火程序、燃烧爆炸分离程序

<!-- STEP2_THINK -->
好的，我现在需要根据用户给出的中文架构模板，生成一份符合AADL标准的代码。模板内容很详细，包含了系统管理器、系统模块、五个分区及其任务、网络接口、处理器/存储器分配、设备列表、数据项和各类程序。我需要将模板中的每一项正确地翻译成AADL的组件类型和实现。

首先，确认AADL的基本结构：一个package，里面包含系统、进程、线程、设备、数据、子程序等组件的声明和实现。模板最外层是“电爆管系统”，对应一个顶层系统Electric_detonator_system，并需要有一个实现Electric_detonator_system.impl。

第一步：确定顶层系统的子组件。模板中“设备”部分列出了5个电爆管设备，所以顶层系统实现中应该包含这5个设备实例。同时还有“电爆管系统管理器”和“电爆管系统模块”两个子系统。因此Electric_detonator_system.impl的subcomponents里要包含：管理器、系统模块、以及5个设备。

第二步：处理“电爆管系统管理器”。模板中管理器只是一个名称，没有内部结构。但为了符合AADL的层次，我需要声明一个系统Electric_detonator_system_manager及其实现。在实现中放置一个Electric_detonator_system_module实例。

第三步：处理“电爆管系统模块”。这是核心部分。模板中模块下有五个分区（process）、一个网络接口（process）。每个分区内部有一个任务（thread）。所以需要声明5个process类型和对应的实现，每个实现中包含一个thread。同时声明网络接口process及其实现。在Electric_detonator_system_module.impl的subcomponents中列出这6个process实例。

第四步：处理分区和任务的具体命名。为了符合AADL标识符规范，我使用英文标识符：Attitude_rocket_ignition_partition、Parachute_launcher_ignition_partition、Deflecter_ignition_partition、Rocket_pack_ignition_partition、Combustion_explosion_separation_partition。任务类似：Attitude_rocket_ignition_task等。网络接口：Electric_detonator_network_interface。

第五步：处理处理器和存储器。模板中列出了处理器和存储器分配，但在AADL中，处理器和存储器是独立的组件。参考当前示例代码，不单独声明处理器和存储器，仅保留process和thread，后续绑定再细化。

第六步：处理设备。五个设备：Electric_detonator_device_1到_5，各自有类型和实现。实现部分可以为空。

第七步：处理总线。模板明确写“总线：0个总线”。所以在AADL代码中不定义任何bus组件，也不在连接中使用总线。

第八步：处理数据。模板中五个数据信号对应AADL中的data组件，命名为Attitude_rocket_ignition_control_signal等。

第九步：处理程序。模块与设备的初始化、运行、终止对应subprogram；分区与网络接口的冷启动、热启动、正常、空闲对应subprogram；五个点火程序也对应subprogram。所有子程序都声明类型和空实现。

第十步：组织连接和关系。参考代码中没有显式连接设备到分区或网络接口，因为总线为0，而且控制信号的映射后续可通过数据端口细化。因此这里省略connection部分，保持高层架构模型简洁。

最后检查：确保所有组件命名与模板中的英文对应一致，数据项中的“降落伞发射器点火控制信号”对应Parachute_launcher_ignition_control_signal。这样，我就按照从模板到AADL的映射规则，一步一步生成了完整的package代码。

<!-- AADL_MODEL -->
package Electric_detonator_system
public
  system Electric_detonator_system
  end Electric_detonator_system;

  system implementation Electric_detonator_system.impl
  subcomponents
    Electric_detonator_system_manager : system Electric_detonator_system_manager.impl;
    Electric_detonator_system_module : system Electric_detonator_system_module.impl;
    Electric_detonator_device_1 : device Electric_detonator_device_1.impl;
    Electric_detonator_device_2 : device Electric_detonator_device_2.impl;
    Electric_detonator_device_3 : device Electric_detonator_device_3.impl;
    Electric_detonator_device_4 : device Electric_detonator_device_4.impl;
    Electric_detonator_device_5 : device Electric_detonator_device_5.impl;
  end Electric_detonator_system.impl;

  system Electric_detonator_system_manager
  end Electric_detonator_system_manager;

  system implementation Electric_detonator_system_manager.impl
  subcomponents
    Electric_detonator_system_module : system Electric_detonator_system_module.impl;
  end Electric_detonator_system_manager.impl;

  system Electric_detonator_system_module
  end Electric_detonator_system_module;

  system implementation Electric_detonator_system_module.impl
  subcomponents
    Attitude_rocket_ignition_partition : process Attitude_rocket_ignition_partition.impl;
    Parachute_launcher_ignition_partition : process Parachute_launcher_ignition_partition.impl;
    Deflecter_ignition_partition : process Deflecter_ignition_partition.impl;
    Rocket_pack_ignition_partition : process Rocket_pack_ignition_partition.impl;
    Combustion_explosion_separation_partition : process Combustion_explosion_separation_partition.impl;
    Electric_detonator_network_interface : process Electric_detonator_network_interface.impl;
  end Electric_detonator_system_module.impl;

  process Attitude_rocket_ignition_partition
  end Attitude_rocket_ignition_partition;

  process implementation Attitude_rocket_ignition_partition.impl
  subcomponents
    Attitude_rocket_ignition_task : thread Attitude_rocket_ignition_task.impl;
  end Attitude_rocket_ignition_partition.impl;

  thread Attitude_rocket_ignition_task
  end Attitude_rocket_ignition_task;

  thread implementation Attitude_rocket_ignition_task.impl
  end Attitude_rocket_ignition_task.impl;

  process Parachute_launcher_ignition_partition
  end Parachute_launcher_ignition_partition;

  process implementation Parachute_launcher_ignition_partition.impl
  subcomponents
    Parachute_launcher_ignition_task : thread Parachute_launcher_ignition_task.impl;
  end Parachute_launcher_ignition_partition.impl;

  thread Parachute_launcher_ignition_task
  end Parachute_launcher_ignition_task;

  thread implementation Parachute_launcher_ignition_task.impl
  end Parachute_launcher_ignition_task.impl;

  process Deflecter_ignition_partition
  end Deflecter_ignition_partition;

  process implementation Deflecter_ignition_partition.impl
  subcomponents
    Deflecter_ignition_task : thread Deflecter_ignition_task.impl;
  end Deflecter_ignition_partition.impl;

  thread Deflecter_ignition_task
  end Deflecter_ignition_task;

  thread implementation Deflecter_ignition_task.impl
  end Deflecter_ignition_task.impl;

  process Rocket_pack_ignition_partition
  end Rocket_pack_ignition_partition;

  process implementation Rocket_pack_ignition_partition.impl
  subcomponents
    Rocket_pack_ignition_task : thread Rocket_pack_ignition_task.impl;
  end Rocket_pack_ignition_partition.impl;

  thread Rocket_pack_ignition_task
  end Rocket_pack_ignition_task;

  thread implementation Rocket_pack_ignition_task.impl
  end Rocket_pack_ignition_task.impl;

  process Combustion_explosion_separation_partition
  end Combustion_explosion_separation_partition;

  process implementation Combustion_explosion_separation_partition.impl
  subcomponents
    Combustion_explosion_separation_task : thread Combustion_explosion_separation_task.impl;
  end Combustion_explosion_separation_partition.impl;

  thread Combustion_explosion_separation_task
  end Combustion_explosion_separation_task;

  thread implementation Combustion_explosion_separation_task.impl
  end Combustion_explosion_separation_task.impl;

  process Electric_detonator_network_interface
  end Electric_detonator_network_interface;

  process implementation Electric_detonator_network_interface.impl
  subcomponents
    Cold_start_program : subprogram Cold_start_program.impl;
    Hot_start_program : subprogram Hot_start_program.impl;
    Normal_program : subprogram Normal_program.impl;
    Idle_program : subprogram Idle_program.impl;
  end Electric_detonator_network_interface.impl;

  subprogram Cold_start_program
  end Cold_start_program;

  subprogram implementation Cold_start_program.impl
  end Cold_start_program.impl;

  subprogram Hot_start_program
  end Hot_start_program;

  subprogram implementation Hot_start_program.impl
  end Hot_start_program.impl;

  subprogram Normal_program
  end Normal_program;

  subprogram implementation Normal_program.impl
  end Normal_program.impl;

  subprogram Idle_program
  end Idle_program;

  subprogram implementation Idle_program.impl
  end Idle_program.impl;

  device Electric_detonator_device_1
  end Electric_detonator_device_1;

  device implementation Electric_detonator_device_1.impl
  end Electric_detonator_device_1.impl;

  device Electric_detonator_device_2
  end Electric_detonator_device_2;

  device implementation Electric_detonator_device_2.impl
  end Electric_detonator_device_2.impl;

  device Electric_detonator_device_3
  end Electric_detonator_device_3;

  device implementation Electric_detonator_device_3.impl
  end Electric_detonator_device_3.impl;

  device Electric_detonator_device_4
  end Electric_detonator_device_4;

  device implementation Electric_detonator_device_4.impl
  end Electric_detonator_device_4.impl;

  device Electric_detonator_device_5
  end Electric_detonator_device_5;

  device implementation Electric_detonator_device_5.impl
  end Electric_detonator_device_5.impl;

  data Attitude_rocket_ignition_control_signal
  end Attitude_rocket_ignition_control_signal;

  data Parachute_launcher_ignition_control_signal
  end Parachute_launcher_ignition_control_signal;

  data Deflecter_ignition_control_signal
  end Deflecter_ignition_control_signal;

  data Rocket_pack_ignition_control_signal
  end Rocket_pack_ignition_control_signal;

  data Combustion_explosion_separation_ignition_control_signal
  end Combustion_explosion_separation_ignition_control_signal;

  subprogram Initialization_program
  end Initialization_program;

  subprogram implementation Initialization_program.impl
  end Initialization_program.impl;

  subprogram Run_program
  end Run_program;

  subprogram implementation Run_program.impl
  end Run_program.impl;

  subprogram Termination_program
  end Termination_program;

  subprogram implementation Termination_program.impl
  end Termination_program.impl;

  subprogram Attitude_rocket_ignition_program
  end Attitude_rocket_ignition_program;

  subprogram implementation Attitude_rocket_ignition_program.impl
  end Attitude_rocket_ignition_program.impl;

  subprogram Parachute_launcher_ignition_program
  end Parachute_launcher_ignition_program;

  subprogram implementation Parachute_launcher_ignition_program.impl
  end Parachute_launcher_ignition_program.impl;

  subprogram Deflecter_ignition_program
  end Deflecter_ignition_program;

  subprogram implementation Deflecter_ignition_program.impl
  end Deflecter_ignition_program.impl;

  subprogram Rocket_pack_ignition_program
  end Rocket_pack_ignition_program;

  subprogram implementation Rocket_pack_ignition_program.impl
  end Rocket_pack_ignition_program.impl;

  subprogram Combustion_explosion_separation_program
  end Combustion_explosion_separation_program;

  subprogram implementation Combustion_explosion_separation_program.impl
  end Combustion_explosion_separation_program.impl;
end Electric_detonator_system;
