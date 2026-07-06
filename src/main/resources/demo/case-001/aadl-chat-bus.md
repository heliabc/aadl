# AADL Chat Demo: Add System Buses

<!-- THINK -->
好的，我现在需要处理的任务是：在原有电爆管系统 AADL 模型的基础上新增总线结构，并保持原模型主体不变。

首先，我检查原始模型的顶层结构。原模型的顶层系统是 Electric_detonator_system，其实现 Electric_detonator_system.impl 中已经包含了系统管理器、电爆管系统模块以及 5 个电爆管设备。因此，新增总线最合适的位置应该是在顶层系统实现中，因为总线属于系统级通信资源，可以作为顶层架构的一部分统一管理。

接着，我观察设备数量。原模型中存在 5 个电爆管设备，分别是 Electric_detonator_device_1 到 Electric_detonator_device_5。为了与这些设备一一对应，我选择新增 5 条总线，分别命名为 Electric_detonator_bus_1、Electric_detonator_bus_2、Electric_detonator_bus_3、Electric_detonator_bus_4 和 Electric_detonator_bus_5。这样可以保持模型结构清晰，也能体现每个电爆管设备具有独立通信通道的含义。

然后，我在 Electric_detonator_system.impl 的 subcomponents 中加入这 5 个总线子组件。由于这些总线属于同一种类型，因此不需要为每条总线都单独定义一种总线类型，而是统一定义一个 Electric_detonator_bus 类型，并让 5 个总线实例都引用 Electric_detonator_bus.impl。

因此，我在顶层系统实现之后补充了如下总线定义：

bus Electric_detonator_bus
end Electric_detonator_bus;

bus implementation Electric_detonator_bus.impl
end Electric_detonator_bus.impl;

最后，我没有继续添加 connections 连接关系。原因是原始模型中的系统模块、网络接口和设备都没有定义端口 features。如果强行添加连接关系，就需要额外编造输入输出端口，这会超出原模型已有信息，也可能造成 AADL 语法不完整。因此，本次修改只进行结构层面的总线扩展，保持其余系统、进程、线程、设备、数据和子程序定义不变。

所以，模型从原始版本到修改版本的核心变化就是：在顶层系统实现中新增 5 个总线实例，并补充一个统一的总线类型及其实现定义。

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

    Electric_detonator_bus_1 : bus Electric_detonator_bus.impl;
    Electric_detonator_bus_2 : bus Electric_detonator_bus.impl;
    Electric_detonator_bus_3 : bus Electric_detonator_bus.impl;
    Electric_detonator_bus_4 : bus Electric_detonator_bus.impl;
    Electric_detonator_bus_5 : bus Electric_detonator_bus.impl;
  end Electric_detonator_system.impl;

  bus Electric_detonator_bus
  end Electric_detonator_bus;

  bus implementation Electric_detonator_bus.impl
  end Electric_detonator_bus.impl;

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
