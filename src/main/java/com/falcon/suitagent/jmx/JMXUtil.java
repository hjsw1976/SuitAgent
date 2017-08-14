/*
 * www.msxf.com Inc.
 * Copyright (c) 2017 All Rights Reserved
 */
package com.falcon.suitagent.jmx;
//             ,%%%%%%%%,
//           ,%%/\%%%%/\%%
//          ,%%%\c "" J/%%%
// %.       %%%%/ o  o \%%%
// `%%.     %%%%    _  |%%%
//  `%%     `%%%%(__Y__)%%'
//  //       ;%%%%`\-/%%%'
// ((       /  `%%%%%%%'
//  \\    .'          |
//   \\  /       \  | |
//    \\/攻城狮保佑) | |
//     \         /_ | |__
//     (___________)))))))                   `\/'
/*
 * 修订记录:
 * long.qian@msxf.com 2017-08-04 14:47 创建
 */

import com.falcon.suitagent.config.AgentConfiguration;
import com.falcon.suitagent.util.*;
import com.falcon.suitagent.vo.docker.ContainerProcInfoToHost;
import com.falcon.suitagent.vo.jmx.JavaExecCommandInfo;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author long.qian@msxf.com
 */
@Slf4j
public class JMXUtil {

    /**
     * 获取本地是否已开启指定的JMX服务
     * @param serverName
     * @return
     */
    public static boolean hasJMXServerInLocal(String serverName){
        if(!StringUtils.isEmpty(serverName)){
            List<VirtualMachineDescriptor> vms = VirtualMachine.list();
            for (VirtualMachineDescriptor desc : vms) {
                File file = new File(desc.displayName());
                if(file.exists()){
                    //java -jar 形式启动的Java应用
                    if(file.toPath().getFileName().toString().equals(serverName)){
                        return true;
                    }
                }else if(hasContainsServerName(desc.displayName(),serverName)){
                    return true;
                }

            }
        }
        return false;
    }

    /**
     * 获取物理机容器中运行的指定Java程序的命令启动字符串
     * @param serverName
     * Java程序服务名，指定为*则返回所有
     * @return
     */
    public static List<JavaExecCommandInfo> getHostJavaCommandInfosFromContainer(String serverName) throws Exception {
        List<JavaExecCommandInfo> javaExecCommandInfos = new ArrayList<>();
        if (StringUtils.isNotEmpty(serverName) && AgentConfiguration.INSTANCE.isDockerRuntime()){
            //仅为Docker容器运行环境时有效
            List<ContainerProcInfoToHost> containerProcInfoToHosts = DockerUtil.getAllHostContainerProcInfos();
            for (ContainerProcInfoToHost containerProcInfoToHost : containerProcInfoToHosts) {
                String appName = DockerUtil.getJavaContainerAppName(containerProcInfoToHost.getContainerId());
                if (appName != null){//只采集指定了appName的容器(必须通过docker run命令的-e参数执行应用名，例如 docker run -e "appName=suitAgent")
                    String tmpDir = containerProcInfoToHost.getProcPath() + "/" + "tmp";
                    File file_tmpDir = new File(tmpDir);
                    if (file_tmpDir.exists()){
                        String[] hsperfDataDirs = file_tmpDir.list((dir, name) -> name.startsWith("hsperfdata"));
                        if (hsperfDataDirs != null) {
                            List<File> files_hsperfData = new ArrayList<>();
                            for (String hsperfDataDir : hsperfDataDirs) {
                                files_hsperfData.add(new File(tmpDir + "/" + hsperfDataDir));
                            }
                            for (File file_hsperfDatum : files_hsperfData) {//编译各个hsperfdata目录
                                if (!file_hsperfDatum.canRead()){
                                    log.error("没有目录的读取权限：{}",file_hsperfDatum.getAbsolutePath());
                                    continue;
                                }
                                String[] pidFiles = file_hsperfDatum.list();
                                if (pidFiles != null) {
                                    String containerIp = DockerUtil.getContainerIp(containerProcInfoToHost.getContainerId());
                                    if (pidFiles.length == 1){  //容器中只有一个Java进程，直接用appName命名
                                        String cmd = HexUtil.filter(FileUtil.getTextFileContent(file_hsperfDatum.getAbsolutePath() + "/" + pidFiles[0]));
                                        if ("*".equals(serverName)){
                                            javaExecCommandInfos.add(new JavaExecCommandInfo(appName,containerIp,cmd));
                                        }else if (cmd.contains(serverName)){
                                            javaExecCommandInfos.add(new JavaExecCommandInfo(appName,containerIp,cmd));
                                        }
                                    }else if (pidFiles.length > 1){ //容器中若有多个java进程，但一个容器只有一个appName，所以用MD5编码命令行的方式进行命名
                                        for (String pidFile : pidFiles) {
                                            String cmd = HexUtil.filter(FileUtil.getTextFileContent(file_hsperfDatum.getAbsolutePath() + "/" + pidFile));
                                            if ("*".equals(serverName)){
                                                javaExecCommandInfos.add(new JavaExecCommandInfo(appName + MD5Util.getMD5(cmd),containerIp,cmd));
                                            }else if (cmd.contains(serverName)){
                                                javaExecCommandInfos.add(new JavaExecCommandInfo(appName + MD5Util.getMD5(cmd),containerIp,cmd));
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }else {
                        log.error("目录不存在：{}",tmpDir);
                    }
                }
            }
        }
        return javaExecCommandInfos;
    }



    /**
     * 获取指定服务名的本地JMX VM 描述对象
     * @param serverName
     * @return
     */
    public static List<VirtualMachineDescriptor> getVmDescByServerName(String serverName){
        List<VirtualMachineDescriptor> vmDescList = new ArrayList<>();
        List<VirtualMachineDescriptor> vms = VirtualMachine.list();
        for (VirtualMachineDescriptor desc : vms) {
            //java -jar 形式启动的Java应用
            if(desc.displayName().matches(".*\\.jar")){
                vmDescList.add(desc);
            }else if(hasContainsServerName(desc.displayName(),serverName)){
                vmDescList.add(desc);
            }
        }
        return vmDescList;
    }

    /**
     * 判断指定的目标服务名是否在探测的展示名中
     * @param displayName
     * @param serverName
     * @return
     */
    public static boolean hasContainsServerName(String displayName,String serverName){
        if (StringUtils.isEmpty(serverName)){
            return false;
        }
        boolean has = true;
        List<String> displaySplit = Arrays.asList(displayName.split("\\s+"));
        for (String s : serverName.split("\\s+")) {
            //boot  start
            if (!displaySplit.contains(s)){
                has = false;
                break;
            }
        }
        return has;
    }

}
