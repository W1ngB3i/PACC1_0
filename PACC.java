import javax.swing.*;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.net.URL;

// 添加HTTP服务器相关导入
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.nio.file.Files;

// 添加AWT事件监听所需导入
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;


/**
 * USB设备监控系统主类
 * 提供图形界面用于监控USB设备的插入和移除，并记录相关信息到日志文件
 */
    public class PACC extends JFrame {
     // UI组件
    private JTextArea logArea;
    private JButton startButton;
    private JButton stopButton;
    private JButton exportButton;
    private JButton settingsButton;
    private JTable deviceTable;
    private DefaultTableModel tableModel;
    private JSpinner intervalSpinner;
    private JLabel statusLabel;
    
    // 新增进程显示组件
    private JTable processTable;
    private DefaultTableModel processTableModel;

    // 系统组件
    private Timer timer;
    private Set<String> previousDrives;
    private PrintWriter deviceLogWriter;
    private PrintWriter processLogWriter;
    private PrintWriter hiddenProcessLogWriter; // 新增：用于记录被隐藏的系统进程
    private PrintWriter cheatLogWriter; // 新增：用于记录作弊程序信息
    private Properties config;

    // 存储设备信息的映射
    private Map<String, DeviceInfo> deviceInfoMap = new HashMap<>();

    // 进程监控相关
    private Set<String> previousProcesses = new HashSet<>();
    private Timer processTimer;

    // 作弊检测相关
    private JFrame cheatAlertFrame; // 全屏警告窗口
    private boolean cheatDetected = false; // 是否检测到作弊程序
    private boolean cheatAlertDismissed = false; // 是否已解除警报
    private static final String CHEAT_ALERT_PASSWORD = "PACC2023"; // 密码设置
    
    // 鼠标点击检测相关
    private int leftClickCount = 0;
    private int rightClickCount = 0;
    private Timer mouseClickResetTimer;
    private long lastLeftClickTime = 0;
    private long lastRightClickTime = 0;
    private static final int MOUSE_CLICK_THRESHOLD = 25;
    private static final int MOUSE_CLICK_TIME_WINDOW = 500; // 0.5秒

    // 标签页组件
    private JTabbedPane tabbedPane;

    // 设置对话框组件
    private JSpinner intervalSettingSpinner;
    private JSpinner autoRefreshSpinner;
    private JCheckBox detailedLogCheckBox;
    private JCheckBox trayNotificationCheckBox;

    /**
     * 构造函数，初始化整个应用程序
     */
    public PACC() {
        instance = this; // 保存实例引用
        loadConfig();
        initializeLogWriters();
        initializeGUI();
        previousDrives = getAvailableDrives();

        // 添加窗口关闭事件，最小化到系统托盘
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                setVisible(false); // 最小化而不是退出
            }
        });

        // 启动进程监控（默认每5秒检查一次）
        startProcessMonitoring();
        
        // 初始化作弊警告窗口
        initializeCheatAlertWindow();
        
        // 初始化鼠标点击监控
        initializeMouseClickMonitoring();
        
        // 启动HTTP服务器
        startHttpServer();
        
        // 添加关闭钩子以确保HTTP服务器在程序退出时正确关闭
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopHttpServer));
    }

    /**
     * 手工创建JSON字符串的方法，替代Gson库
     */
    private static String toJson(Object obj) {
        if (obj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) obj;
            return mapToJson(map);
        } else if (obj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) obj;
            return listToJson(list);
        } else if (obj instanceof String) {
            return "\"" + escapeJsonString((String) obj) + "\"";
        } else if (obj instanceof Number || obj instanceof Boolean) {
            return obj.toString();
        } else if (obj == null) {
            return "null";
        } else {
            return "\"" + escapeJsonString(obj.toString()) + "\"";
        }
    }

    private static String mapToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            sb.append("\"").append(escapeJsonString(entry.getKey())).append("\":");
            sb.append(toJson(entry.getValue()));
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private static String listToJson(List<Object> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(toJson(list.get(i)));
        }
        sb.append("]");
        return sb.toString();
    }

    private static String escapeJsonString(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\b", "\\b")
                  .replace("\f", "\\f")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    /**
     * 加载配置文件，如果不存在则使用默认配置
     */
    private void loadConfig() {
        config = new Properties();
        try (InputStream input = new FileInputStream("usbmonitor.properties")) {
            config.load(input);
        } catch (IOException e) {
            // 默认配置
            config.setProperty("checkInterval", "3000");
            config.setProperty("logDirectory", "logs");
            config.setProperty("autoRefreshInterval", "30");
            config.setProperty("enableDetailedLogging", "true");
            config.setProperty("enableTrayNotifications", "true");
            config.setProperty("processCheckInterval", "5000"); // 新增：进程检查间隔（毫秒）
        }
    }

    /**
     * 保存配置到文件
     */
    private void saveConfig() {
        try (OutputStream output = new FileOutputStream("usbmonitor.properties")) {
            config.store(output, "USB Monitor Configuration");
        } catch (IOException e) {
            logMessage("保存配置失败: " + e.getMessage());
        }
    }

    // 添加HTTP服务器实例
    private HttpServer httpServer;
    
    // 添加静态实例引用
    private static PACC instance;
    
    // 定义常见的系统进程列表
    private final Set<String> systemProcesses = new HashSet<>(Arrays.asList(
        "svchost.exe", "dllhost.exe", "conhost.exe", "taskhost.exe", "taskhostw.exe",
        "services.exe", "lsass.exe", "lsm.exe", "wininit.exe", "winlogon.exe",
        "csrss.exe", "smss.exe", "explorer.exe", "dwm.exe", "fontdrvhost.exe",
        "audiodg.exe", "wlanext.exe", "spoolsv.exe"
    ));

    /**
     * 初始化日志写入器
     */
    private void initializeLogWriters() {
        try {
            // 创建logs目录（如果不存在）
            String logDirName = config.getProperty("logDirectory", "logs");
            File logDir = new File(logDirName);
            if (!logDir.exists()) {
                logDir.mkdirs();
            }

            // 初始化设备日志写入器
            deviceLogWriter = new PrintWriter(new FileWriter(logDirName + "/device-info.log", true), true);

            // 初始化进程日志写入器
            processLogWriter = new PrintWriter(new FileWriter(logDirName + "/process-activity.log", true), true);
            
            // 初始化隐藏进程日志写入器
            hiddenProcessLogWriter = new PrintWriter(new FileWriter(logDirName + "/hidden-processes.txt", true), true);
            
            // 初始化作弊日志写入器
            cheatLogWriter = new PrintWriter(new FileWriter(logDirName + "/cheat-detection.log", true), true);
        } catch (IOException e) {
            System.err.println("无法初始化日志文件: " + e.getMessage());
            // 不弹窗，避免阻塞
        }
    }
    // ... existing code ...

/**
 * 初始化图形用户界面
 */
private void initializeGUI() {
    setTitle("USB设备监控系统 - PACC反作弊系统");
    setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
    setSize(1200, 700);
    setLocationRelativeTo(null);
    setMinimumSize(new Dimension(800, 600));

    // 设置应用程序图标
    setApplicationIcon();

    // 定义颜色主题
    Color PRIMARY_COLOR = new Color(51, 51, 51);          // 主色调 - 深灰色
    Color SECONDARY_COLOR = new Color(68, 68, 68);        // 辅助色 - 稍浅的灰色
    Color TEXT_COLOR = Color.LIGHT_GRAY;                   // 文字颜色
    Color HOVER_COLOR = new Color(85, 85, 85);            // 悬停颜色
    Color ACCENT_COLOR = new Color(33, 150, 243);         // 强调色 - 蓝色

    JPanel mainPanel = new JPanel(new BorderLayout());

    // 创建左侧导航栏
    JPanel sidebar = createSidebar(PRIMARY_COLOR, SECONDARY_COLOR, TEXT_COLOR, HOVER_COLOR, ACCENT_COLOR);
    mainPanel.add(sidebar, BorderLayout.WEST);

    // 创建带颜色的主题按钮面板
    JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
    buttonPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
    
    startButton = new JButton("▶ 开始监控");
    stopButton = new JButton("⏹ 停止监控");
    exportButton = new JButton("📤 导出日志");
    settingsButton = new JButton("⚙ 设置");
    
    // 设置按钮样式
    styleButtons();
    
    stopButton.setEnabled(false);

    buttonPanel.add(startButton);
    buttonPanel.add(stopButton);
    buttonPanel.add(exportButton);
    buttonPanel.add(settingsButton);
    mainPanel.add(buttonPanel, BorderLayout.NORTH);

    // 使用卡片布局替代分割面板，提供更现代的外观
    JPanel contentPanel = new JPanel(new BorderLayout());
    
    // 日志区
    logArea = new JTextArea();
    logArea.setEditable(false);
    logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    logArea.setBackground(new Color(245, 245, 245));
    JScrollPane logScrollPane = new JScrollPane(logArea);
    logScrollPane.setBorder(BorderFactory.createTitledBorder("系统日志"));

    // 创建表格模型并设置列名
    String[] columnNames = {
        "驱动器", "卷标", "序列号", "制造商", "型号", 
        "总容量(GB)", "可用空间(GB)", "文件系统", "接口类型", "设备类型",
        "插入时间", "最后活动"
    };

    // 初始化 tableModel
    tableModel = new DefaultTableModel(columnNames, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };

    deviceTable = new JTable(tableModel);
    deviceTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    deviceTable.getTableHeader().setReorderingAllowed(false);
    JScrollPane tableScrollPane = new JScrollPane(deviceTable);
    tableScrollPane.setBorder(BorderFactory.createTitledBorder("已连接的USB设备"));
    
    // 设置表格列宽
    setDeviceTableColumnWidths();
    
    // 进程表格
    String[] processColumnNames = {"进程名称", "状态", "CPU使用率", "内存使用率", "启动时间"};
    processTableModel = new DefaultTableModel(processColumnNames, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    
    processTable = new JTable(processTableModel);
    processTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    processTable.getTableHeader().setReorderingAllowed(false);
    JScrollPane processTableScrollPane = new JScrollPane(processTable);
    processTableScrollPane.setBorder(BorderFactory.createTitledBorder("系统进程"));
    
    // 设置进程表列宽
    setProcessTableColumnWidths();
    
    // 使用标签页来显示设备和进程信息
    tabbedPane = new JTabbedPane(); // 注意：这里需要声明tabbedPane变量
    tabbedPane.addTab("💾 USB设备", tableScrollPane);
    tabbedPane.addTab("🧩 系统进程", processTableScrollPane);
    tabbedPane.addTab("📜 系统日志", logScrollPane);
    
    // 添加实时统计面板
    JPanel statsPanel = createStatsPanel();
    JSplitPane topSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tabbedPane, statsPanel);
    topSplitPane.setDividerLocation(800); 
    topSplitPane.setResizeWeight(0.7);  

    contentPanel.add(topSplitPane, BorderLayout.CENTER);
    mainPanel.add(contentPanel, BorderLayout.CENTER);

    // 添加状态栏
    statusLabel = new JLabel("就绪");
    statusLabel.setBorder(BorderFactory.createEtchedBorder());
    mainPanel.add(statusLabel, BorderLayout.SOUTH);

    add(mainPanel);


    // 事件监听
    startButton.addActionListener(e -> startMonitoring());
    stopButton.addActionListener(e -> stopMonitoring());
    exportButton.addActionListener(e -> exportLogs());
    settingsButton.addActionListener(e -> showSettings());

        // 初始化系统托盘
        initializeSystemTray();
    }
    
    
    /**
     * 设置应用程序图标
     */
    private void setApplicationIcon() {
        try {
            BufferedImage icon = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = icon.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            
            // 绘制USB图标
            g2d.setColor(new Color(33, 150, 243)); // 蓝色
            g2d.fillRoundRect(8, 4, 16, 24, 4, 4);
            g2d.setColor(Color.WHITE);
            g2d.fillRect(12, 8, 8, 4);
            g2d.fillRect(12, 14, 8, 4);
            g2d.fillRect(12, 20, 8, 4);
            
            g2d.dispose();
            setIconImage(icon);
        } catch (Exception e) {
            // 静默忽略图标设置错误
        }
    }
    
    /**
     * 设置按钮样式
     */
    private void styleButtons() {
        // 设置按钮背景色和前景色
        startButton.setBackground(new Color(76, 175, 80)); // 绿色
        startButton.setForeground(Color.WHITE);
        startButton.setFocusPainted(false);
        startButton.setBorder(BorderFactory.createEmptyBorder(5, 15, 5, 15));
        
        stopButton.setBackground(new Color(244, 67, 54)); // 红色
        stopButton.setForeground(Color.WHITE);
        stopButton.setFocusPainted(false);
        stopButton.setBorder(BorderFactory.createEmptyBorder(5, 15, 5, 15));
        
        exportButton.setBackground(new Color(33, 150, 243)); // 蓝色
        exportButton.setForeground(Color.WHITE);
        exportButton.setFocusPainted(false);
        exportButton.setBorder(BorderFactory.createEmptyBorder(5, 15, 5, 15));
        
        settingsButton.setBackground(new Color(158, 158, 158)); // 灰色
        settingsButton.setForeground(Color.WHITE);
        settingsButton.setFocusPainted(false);
        settingsButton.setBorder(BorderFactory.createEmptyBorder(5, 15, 5, 15));
    }
    
    /**
     * 设置设备表列宽
     */
    private void setDeviceTableColumnWidths() {
        if (deviceTable.getColumnCount() > 0) {
            deviceTable.getColumnModel().getColumn(0).setPreferredWidth(60);  // 驱动器
            deviceTable.getColumnModel().getColumn(1).setPreferredWidth(80);  // 卷标
            deviceTable.getColumnModel().getColumn(2).setPreferredWidth(100); // 序列号
            deviceTable.getColumnModel().getColumn(3).setPreferredWidth(80);  // 制造商
            deviceTable.getColumnModel().getColumn(4).setPreferredWidth(120); // 型号
            deviceTable.getColumnModel().getColumn(5).setPreferredWidth(90);  // 总容量
            deviceTable.getColumnModel().getColumn(6).setPreferredWidth(90);  // 可用空间
            deviceTable.getColumnModel().getColumn(7).setPreferredWidth(80);  // 文件系统
            deviceTable.getColumnModel().getColumn(8).setPreferredWidth(80);  // 接口类型
            deviceTable.getColumnModel().getColumn(9).setPreferredWidth(80);  // 设备类型
            deviceTable.getColumnModel().getColumn(10).setPreferredWidth(120); // 插入时间
            deviceTable.getColumnModel().getColumn(11).setPreferredWidth(120); // 最后活动
        }
    }
    
    /**
     * 设置进程表列宽
     */
    private void setProcessTableColumnWidths() {
        if (processTable.getColumnCount() > 0) {
            processTable.getColumnModel().getColumn(0).setPreferredWidth(200); // 进程名称
            processTable.getColumnModel().getColumn(1).setPreferredWidth(80);  // 状态
            processTable.getColumnModel().getColumn(2).setPreferredWidth(100); // CPU使用率
            processTable.getColumnModel().getColumn(3).setPreferredWidth(100); // 内存使用率
            processTable.getColumnModel().getColumn(4).setPreferredWidth(150); // 启动时间
        }
    }
    
    /**
     * 创建统计面板
     */
    private JPanel createStatsPanel() {
        JPanel statsPanel = new JPanel();
        statsPanel.setLayout(new BoxLayout(statsPanel, BoxLayout.Y_AXIS));
        statsPanel.setBorder(BorderFactory.createTitledBorder("实时统计"));
        
        // 添加统计信息标签
        JLabel totalDevicesLabel = new JLabel("设备总数: 0");
        totalDevicesLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        totalDevicesLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        
        JLabel totalProcessesLabel = new JLabel("进程总数: 0");
        totalProcessesLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        totalProcessesLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        
        JLabel monitoringStatusLabel = new JLabel("监控状态: 未启动");
        monitoringStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        monitoringStatusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        
        // 添加分隔线
        JSeparator separator = new JSeparator(SwingConstants.HORIZONTAL);
        separator.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        // 添加系统信息
        JLabel systemInfoLabel = new JLabel("<html><b>系统信息:</b></html>");
        systemInfoLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        systemInfoLabel.setBorder(BorderFactory.createEmptyBorder(10, 10, 5, 10));
        
        JLabel osLabel = new JLabel("操作系统: " + System.getProperty("os.name"));
        osLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        osLabel.setBorder(BorderFactory.createEmptyBorder(2, 20, 2, 10));
        
        JLabel javaLabel = new JLabel("Java版本: " + System.getProperty("java.version"));
        javaLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        javaLabel.setBorder(BorderFactory.createEmptyBorder(2, 20, 2, 10));
        
        statsPanel.add(totalDevicesLabel);
        statsPanel.add(totalProcessesLabel);
        statsPanel.add(monitoringStatusLabel);
        statsPanel.add(separator);
        statsPanel.add(systemInfoLabel);
        statsPanel.add(osLabel);
        statsPanel.add(javaLabel);
        
        return statsPanel;
    }

    /**
     * 初始化系统托盘功能
     */
    private void initializeSystemTray() {
        if (SystemTray.isSupported()) {
            SystemTray tray = SystemTray.getSystemTray();
            PopupMenu popup = new PopupMenu();
            MenuItem openItem = new MenuItem("打开");
            MenuItem exitItem = new MenuItem("退出");

            openItem.addActionListener(e -> {
                setVisible(true);
                setExtendedState(JFrame.NORMAL);
            });
            exitItem.addActionListener(e -> System.exit(0));

            popup.add(openItem);
            popup.addSeparator();
            popup.add(exitItem);

            Image image = createTrayImage();
            TrayIcon trayIcon = new TrayIcon(image, "USB监控系统", popup);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> {
                setVisible(true);
                setExtendedState(JFrame.NORMAL);
            });

            try {
                tray.add(trayIcon);
            } catch (AWTException ex) {
                logMessage("添加系统托盘图标失败: " + ex.getMessage());
            }
        }
    }

/**
 * 创建左侧导航栏
 */
private JPanel createSidebar(Color PRIMARY_COLOR, Color SECONDARY_COLOR, Color TEXT_COLOR, Color HOVER_COLOR, Color ACCENT_COLOR) {
    JPanel sidebar = new JPanel(new BorderLayout());
    sidebar.setBackground(PRIMARY_COLOR);
    sidebar.setPreferredSize(new Dimension(200, 600));
    sidebar.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.GRAY));

    // 创建顶部品牌区域
    JPanel brandPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
    brandPanel.setBackground(SECONDARY_COLOR);
    brandPanel.setBorder(BorderFactory.createEmptyBorder(20, 10, 10, 10));
    brandPanel.setPreferredSize(new Dimension(200, 80));

    JLabel brandLabel = new JLabel("PACC");
    brandLabel.setForeground(Color.CYAN);
    brandLabel.setFont(new Font("微软雅黑", Font.BOLD, 20));

    JLabel subtitleLabel = new JLabel("反作弊系统");
    subtitleLabel.setForeground(TEXT_COLOR);
    subtitleLabel.setFont(new Font("微软雅黑", Font.PLAIN, 12));

    brandPanel.add(brandLabel);
    brandPanel.add(subtitleLabel);

    // 创建导航菜单
    JPanel menuPanel = new JPanel(new GridLayout(0, 1));
    menuPanel.setBackground(PRIMARY_COLOR);
    menuPanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 0));

    String[] menuItems = {"仪表板", "USB设备", "系统进程", "系统日志", "系统设置", "管理员", "飞书登录", "QQ登录"};
    
    // 创建图标数组（使用默认图标或加载实际图标）
    Icon[] icons = {
    null, // 仪表板
    null, // USB设备
    null, // 系统进程
    null, // 系统日志
    null, // 系统设置
    null, // 管理员
    null, // 飞书登录
    null  // QQ登录
};

    // 为每个菜单项创建按钮并添加到菜单面板
    for (int i = 0; i < menuItems.length; i++) {
        JButton menuItem = createMenuItem(menuItems[i], icons[i], PRIMARY_COLOR, TEXT_COLOR, HOVER_COLOR);
        menuPanel.add(menuItem);
    }

    // 创建底部信息区域
    JPanel footerPanel = new JPanel(new BorderLayout());
    footerPanel.setBackground(SECONDARY_COLOR);
    footerPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 20, 10));
    footerPanel.setPreferredSize(new Dimension(200, 60));

    JLabel versionLabel = new JLabel("POTATOTV Server 2023");
    versionLabel.setForeground(TEXT_COLOR);
    versionLabel.setFont(new Font("微软雅黑", Font.PLAIN, 10));

    JLabel joinLabel = new JLabel("点击加入交流群");
    joinLabel.setForeground(Color.CYAN);
    joinLabel.setFont(new Font("微软雅黑", Font.PLAIN, 10));
    joinLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    
    // 添加点击事件
    joinLabel.addMouseListener(new java.awt.event.MouseAdapter() {
        public void mouseClicked(java.awt.event.MouseEvent evt) {
            logMessage("用户点击了'加入交流群'");
            openCommunityLink();
        }
        
        public void mouseEntered(java.awt.event.MouseEvent evt) {
            joinLabel.setText("<html><u>点击加入交流群</u></html>");
        }
        
        public void mouseExited(java.awt.event.MouseEvent evt) {
            joinLabel.setText("点击加入交流群");
        }
    });

    footerPanel.add(versionLabel, BorderLayout.NORTH);
    footerPanel.add(joinLabel, BorderLayout.SOUTH);

    sidebar.add(brandPanel, BorderLayout.NORTH);
    sidebar.add(new JScrollPane(menuPanel), BorderLayout.CENTER);
    sidebar.add(footerPanel, BorderLayout.SOUTH);

    return sidebar;
}

/**
 * 创建菜单项按钮
 */
private JButton createMenuItem(String text, Icon icon, Color backgroundColor, Color textColor, Color hoverColor) {
    JButton menuItem = new JButton(text);
    
    // 处理可能不存在的图标资源
    if (icon != null) {
        try {
            menuItem.setIcon(icon);
        } catch (Exception e) {
            System.err.println("无法加载图标: " + e.getMessage());
        }
    }
    
    menuItem.setHorizontalAlignment(SwingConstants.LEFT);
    menuItem.setBackground(backgroundColor);
    menuItem.setForeground(textColor);
    menuItem.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));
    menuItem.setFocusPainted(false);
    menuItem.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    menuItem.setContentAreaFilled(false);
    menuItem.setOpaque(true);
    
    // 添加悬停效果
    menuItem.addMouseListener(new java.awt.event.MouseAdapter() {
        public void mouseEntered(java.awt.event.MouseEvent evt) {
            menuItem.setBackground(hoverColor);
        }
        
        public void mouseExited(java.awt.event.MouseEvent evt) {
            menuItem.setBackground(backgroundColor);
        }
    });
    
    // 添加点击事件处理
    menuItem.addActionListener(e -> handleMenuClick(text));
    
    return menuItem;
}

/**
 * 处理菜单点击事件
 */
private void handleMenuClick(String menuText) {
    switch (menuText) {
        case "仪表板":
            // 显示仪表板内容
            showDashboard();
            break;
        case "USB设备":
            // 显示USB设备列表
            showUsbDevices();
            break;
        case "系统进程":
            // 显示系统进程列表
            showSystemProcesses();
            break;
        case "系统日志":
            // 显示系统日志
            showSystemLogs();
            break;
        case "系统设置":
            // 显示系统设置
            showSettings();
            break;
        case "管理员":
            // 显示管理员功能
            showAdminFeatures();
            break;
        case "飞书登录":
            // 飞书登录功能
            loginWithFeishu();
            break;
        case "QQ登录":
            // QQ登录功能
            loginWithQQ();
            break;
        default:
            logMessage("未识别的菜单项: " + menuText);
    }
}

/**
 * 显示仪表板内容
 */
private void showDashboard() {
    // 设置标签页显示"USB设备"选项卡
    tabbedPane.setSelectedIndex(0);
    logMessage("切换到仪表板视图");
}

/**
 * 显示USB设备列表
 */
private void showUsbDevices() {
    // 设置标签页显示"USB设备"选项卡
    tabbedPane.setSelectedIndex(0);
    logMessage("切换到USB设备视图");
}

/**
 * 显示系统进程列表
 */
private void showSystemProcesses() {
    // 设置标签页显示"系统进程"选项卡
    tabbedPane.setSelectedIndex(1);
    logMessage("切换到系统进程视图");
}

/**
 * 显示系统日志
 */
private void showSystemLogs() {
    // 设置标签页显示"系统日志"选项卡
    tabbedPane.setSelectedIndex(2);
    logMessage("切换到系统日志视图");
}

/**
 * 显示系统设置
 */
private void showSettings() {
    // 显示设置对话框
    showSettingsDialog();
    logMessage("打开系统设置");
}

/**
 * 显示管理员功能
 */
private void showAdminFeatures() {
    // 这里可以添加管理员相关的功能
    JOptionPane.showMessageDialog(this, 
        "<html><div style='text-align: center;'>管理员功能<br/>正在开发中...</div></html>",
        "管理员功能", 
        JOptionPane.INFORMATION_MESSAGE);
    logMessage("访问管理员功能");
}

/**
 * 飞书登录功能
 */
private void loginWithFeishu() {
    // 这里可以添加飞书登录逻辑
    JOptionPane.showMessageDialog(this, 
        "<html><div style='text-align: center;'>飞书登录<br/>功能暂未实现</div></html>",
        "飞书登录", 
        JOptionPane.INFORMATION_MESSAGE);
    logMessage("尝试飞书登录");
}

/**
 * QQ登录功能
 */
private void loginWithQQ() {
    // 这里可以添加QQ登录逻辑
    JOptionPane.showMessageDialog(this, 
        "<html><div style='text-align: center;'>QQ登录<br/>功能暂未实现</div></html>",
        "QQ登录", 
        JOptionPane.INFORMATION_MESSAGE);
    logMessage("尝试QQ登录");
}

/**
 * 打开社区链接
 */
private void openCommunityLink() {
    try {
        Desktop.getDesktop().browse(new URI("https://qm.qq.com/q/SlYVmtVRyC"));
    } catch (Exception e) {
        logMessage("无法打开社区链接: " + e.getMessage());
        JOptionPane.showMessageDialog(this, 
            "无法打开社区链接，请手动访问 https://qm.qq.com/q/SlYVmtVRyC",
            "错误", 
            JOptionPane.ERROR_MESSAGE);
    }
}


/**
 * 显示设置对话框
 */
private void showSettingsDialog() {
    JFrame settingsDialog = new JFrame("系统设置");
    settingsDialog.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    settingsDialog.setSize(400, 300);
    settingsDialog.setLocationRelativeTo(this);
    
    JPanel panel = new JPanel(new GridBagLayout());
    GridBagConstraints gbc = new GridBagConstraints();
    gbc.insets = new Insets(10, 10, 10, 10);
    
    // 检查间隔设置
    gbc.gridx = 0; gbc.gridy = 0;
    panel.add(new JLabel("检查间隔(ms):"), gbc);
    
    gbc.gridx = 1; gbc.gridy = 0;
    intervalSettingSpinner = new JSpinner(new SpinnerNumberModel(
        Integer.parseInt(config.getProperty("checkInterval", "3000")), 100, 10000, 100));
    panel.add(intervalSettingSpinner, gbc);
    
    // 自动刷新间隔设置
    gbc.gridx = 0; gbc.gridy = 1;
    panel.add(new JLabel("自动刷新间隔(s):"), gbc);
    
    gbc.gridx = 1; gbc.gridy = 1;
    autoRefreshSpinner = new JSpinner(new SpinnerNumberModel(
        Integer.parseInt(config.getProperty("autoRefreshInterval", "30")), 1, 60, 1));
    panel.add(autoRefreshSpinner, gbc);
    
    // 详细日志设置
    gbc.gridx = 0; gbc.gridy = 2;
    panel.add(new JLabel("启用详细日志:"), gbc);
    
    gbc.gridx = 1; gbc.gridy = 2;
    detailedLogCheckBox = new JCheckBox();
    detailedLogCheckBox.setSelected(Boolean.parseBoolean(config.getProperty("enableDetailedLogging", "true")));
    panel.add(detailedLogCheckBox, gbc);
    
    // 托盘通知设置
    gbc.gridx = 0; gbc.gridy = 3;
    panel.add(new JLabel("启用托盘通知:"), gbc);
    
    gbc.gridx = 1; gbc.gridy = 3;
    trayNotificationCheckBox = new JCheckBox();
    trayNotificationCheckBox.setSelected(Boolean.parseBoolean(config.getProperty("enableTrayNotifications", "true")));
    panel.add(trayNotificationCheckBox, gbc);
    
    // 保存按钮
    gbc.gridx = 0; gbc.gridy = 4;
    gbc.gridwidth = 2;
    JButton saveButton = new JButton("保存");
    saveButton.addActionListener(e -> {
        config.setProperty("checkInterval", String.valueOf(intervalSettingSpinner.getValue()));
        config.setProperty("autoRefreshInterval", String.valueOf(autoRefreshSpinner.getValue()));
        config.setProperty("enableDetailedLogging", String.valueOf(detailedLogCheckBox.isSelected()));
        config.setProperty("enableTrayNotifications", String.valueOf(trayNotificationCheckBox.isSelected()));
        saveConfig();
        intervalSpinner.setValue(intervalSettingSpinner.getValue());
        JOptionPane.showMessageDialog(settingsDialog, "设置已保存", "保存成功", JOptionPane.INFORMATION_MESSAGE);
        settingsDialog.dispose();
    });
    panel.add(saveButton, gbc);
    
    settingsDialog.add(panel);
    settingsDialog.setVisible(true);
}

    /**
     * 创建托盘图标
     */
    private Image createTrayImage() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setColor(Color.BLUE);
        g2d.fillRoundRect(4, 2, 8, 12, 2, 2);
        g2d.setColor(Color.WHITE);
        g2d.fillRect(6, 4, 4, 2);
        g2d.fillRect(6, 8, 4, 2);
        g2d.fillRect(6, 12, 4, 2);
        g2d.dispose();
        return image;
    }

    /**
     * 开始监控USB设备
     */
    private void startMonitoring() {
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        exportButton.setEnabled(false);
        intervalSpinner.setEnabled(false);

        int interval = (int) intervalSpinner.getValue();
        config.setProperty("checkInterval", String.valueOf(interval));
        saveConfig();

        timer = new Timer(interval, e -> checkUsbDevices());
        timer.start();

        logMessage("开始监控USB设备...");
        writeToLogs("开始监控USB设备...", true, true);
        statusLabel.setText("正在监控...");
    }

    /**
     * 停止监控USB设备
     */
    private void stopMonitoring() {
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        exportButton.setEnabled(true);
        intervalSpinner.setEnabled(true);

        if (timer != null) {
            timer.stop();
        }

        if (processTimer != null) {
            processTimer.stop();
        }

        logMessage("停止监控USB设备");
        writeToLogs("停止监控USB设备", true, true);
        statusLabel.setText("监控已停止");
    }

    /**
     * 检查USB设备状态
     */
    private void checkUsbDevices() {
        Set<String> currentDrives = getAvailableDrives();

        for (String drive : currentDrives) {
            if (!previousDrives.contains(drive)) {
                handleDeviceInserted(drive);
            }
        }

        for (String drive : previousDrives) {
            if (!currentDrives.contains(drive)) {
                handleDeviceRemoved(drive);
            }
        }

        previousDrives = currentDrives;
        refreshDeviceInfo();
        checkInputDevices();
    }

    /**
     * 获取当前可用的驱动器列表
     */
    private Set<String> getAvailableDrives() {
        Set<String> drives = new HashSet<>();
        File[] roots = File.listRoots();
        for (File root : roots) {
            drives.add(root.getAbsolutePath());
        }
        return drives;
    }

    /**
     * 刷新设备信息
     */
    private void refreshDeviceInfo() {
        for (String drivePath : deviceInfoMap.keySet()) {
            DeviceInfo deviceInfo = deviceInfoMap.get(drivePath);
            deviceInfo.lastActivity = LocalDateTime.now();
            updateDeviceTable();
        }
    }

    /**
     * 处理设备插入事件
     */
    private void handleDeviceInserted(String drivePath) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String message = String.format("[%s] USB设备已插入: %s", timestamp, drivePath);
        logMessage(message);
        writeToLogs(message, true, true);

        DeviceInfo deviceInfo = getDetailedDeviceInfo(drivePath);
        deviceInfoMap.put(drivePath, deviceInfo);
        updateDeviceTable();

        showTrayNotification("USB设备插入", "检测到新的USB设备: " + drivePath);

        String deviceDetails = String.format(
            "设备详情 - 卷标: %s, 序列号: %s, 制造商: %s, 型号: %s, 总空间: %.2f GB, 可用空间: %.2f GB, 文件系统: %s",
            deviceInfo.volumeLabel,
            deviceInfo.serialNumber,
            deviceInfo.manufacturer,
            deviceInfo.model,
            deviceInfo.totalSpace / (1024.0 * 1024.0 * 1024.0),
            deviceInfo.freeSpace / (1024.0 * 1024.0 * 1024.0),
            deviceInfo.fileSystem
        );
        logMessage("  " + deviceDetails);
        writeToLogs("  " + deviceDetails, true, true);
    }

    /**
     * 处理设备移除事件
     */
    private void handleDeviceRemoved(String drivePath) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String message = String.format("[%s] USB设备已移除: %s", timestamp, drivePath);
        logMessage(message);
        writeToLogs(message, true, true);

        showTrayNotification("USB设备移除", "USB设备已被移除: " + drivePath);
        deviceInfoMap.remove(drivePath);
        updateDeviceTable();
    }

    /**
     * 获取设备详细信息
     */
    private DeviceInfo getDetailedDeviceInfo(String drivePath) {
        File drive = new File(drivePath);
        DeviceInfo info = new DeviceInfo();
        info.drivePath = drivePath;
        info.insertTime = LocalDateTime.now();
        info.lastActivity = LocalDateTime.now();

        try {
            info.volumeLabel = drive.getCanonicalFile().getName();
        } catch (IOException e) {
            info.volumeLabel = "未知";
        }

        info.totalSpace = drive.getTotalSpace();
        info.freeSpace = drive.getFreeSpace();
        info.usableSpace = drive.getUsableSpace();
        info.fileSystem = getFileSystemType(drivePath);
        info.serialNumber = getDriveSerialNumber(drivePath);

        try {
            ProcessBuilder processBuilder = new ProcessBuilder("wmic", "diskdrive", "get", "model,manufacturer,caption");
            Process process = processBuilder.start();
            Scanner scanner = new Scanner(process.getInputStream());
            StringBuilder output = new StringBuilder();
            while (scanner.hasNextLine()) {
                output.append(scanner.nextLine()).append("\n");
            }
            scanner.close();
            process.waitFor();

            String[] lines = output.toString().split("\n");
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].contains("USB") || lines[i].contains(drivePath.substring(0, 1))) {
                    String[] parts = lines[i].trim().split("\\s{2,}");
                    if (parts.length >= 3) {
                        info.model = parts[2].trim();
                        info.manufacturer = parts[1].trim();
                    } else if (parts.length >= 2) {
                        info.model = parts[1].trim();
                        info.manufacturer = "未知";
                    }
                    break;
                }
            }
        } catch (Exception e) {
            info.model = "未知";
            info.manufacturer = "未知";
            logMessage("获取设备型号和制造商信息时出错: " + e.getMessage());
        }

        if (info.model == null || info.model.isEmpty()) info.model = "未知设备";
        if (info.manufacturer == null || info.manufacturer.isEmpty()) info.manufacturer = "未知制造商";

        try {
            ProcessBuilder processBuilder = new ProcessBuilder("wmic", "diskdrive", "get", "interfaceType,caption");
            Process process = processBuilder.start();
            Scanner scanner = new Scanner(process.getInputStream());
            StringBuilder output = new StringBuilder();
            while (scanner.hasNextLine()) {
                output.append(scanner.nextLine()).append("\n");
            }
            scanner.close();
            process.waitFor();

            String[] lines = output.toString().split("\n");
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].contains("USB") || lines[i].contains(drivePath.substring(0, 1))) {
                    String[] parts = lines[i].trim().split("\\s{2,}");
                    if (parts.length >= 2) {
                        info.interfaceType = parts[1].trim();
                    }
                    break;
                }
            }
        } catch (Exception e) {
            logMessage("获取设备接口类型时出错: " + e.getMessage());
        }

        if (info.interfaceType == null || info.interfaceType.isEmpty()) info.interfaceType = "USB";

        return info;
    }

    /**
     * 获取文件系统类型
     */
    private String getFileSystemType(String drivePath) {
        File drive = new File(drivePath);
        if (drive.getTotalSpace() > 0) {
            long total = drive.getTotalSpace();
            if (total < 4L * 1024 * 1024 * 1024) return "FAT16";
            else if (total < 32L * 1024 * 1024 * 1024) return "FAT32";
            else return "NTFS/exFAT";
        }
        return "未知";
    }

    /**
     * 获取驱动器序列号
     */
    private String getDriveSerialNumber(String drivePath) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("wmic", "diskdrive", "get", "serialnumber,model");
            Process process = processBuilder.start();
            Scanner scanner = new Scanner(process.getInputStream());
            StringBuilder output = new StringBuilder();
            while (scanner.hasNextLine()) {
                output.append(scanner.nextLine()).append("\n");
            }
            scanner.close();
            process.waitFor();

            String[] lines = output.toString().split("\n");
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].contains("USB") || lines[i].contains(drivePath.substring(0, 1))) {
                    String[] parts = lines[i].trim().split("\\s{2,}");
                    if (parts.length >= 1) return parts[0].trim();
                }
            }
        } catch (Exception e) {
            logMessage("获取设备序列号时出错: " + e.getMessage());
        }
        return "SN" + Math.abs(drivePath.hashCode());
    }

// 修改updateDeviceTable方法中的进程信息显示
private void updateDeviceTable() {
    tableModel.setRowCount(0);
    for (Map.Entry<String, DeviceInfo> entry : deviceInfoMap.entrySet()) {
        DeviceInfo info = entry.getValue();
        Object[] rowData = {
            info.drivePath,
            info.volumeLabel,
            info.serialNumber,
            info.manufacturer,
            info.model,
            String.format("%.2f GB", info.totalSpace / (1024.0 * 1024.0 * 1024.0)),
            String.format("%.2f GB", info.freeSpace / (1024.0 * 1024.0 * 1024.0)),
            String.format("%.2f GB", info.usableSpace / (1024.0 * 1024.0 * 1024.0)),
            info.fileSystem,
            info.interfaceType,
            info.deviceType != null ? info.deviceType : "存储设备",
            info.insertTime.format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")),
            info.lastActivity.format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
        };
        tableModel.addRow(rowData);
    }

    java.util.List<DeviceInfo> inputDevices = getInputDevices();
    for (DeviceInfo device : inputDevices) {
        Object[] rowData = {
            "N/A",
            "N/A",
            "N/A",
            device.manufacturer,
            device.model,
            "N/A",
            "N/A",
            "N/A",
            "N/A",
            device.interfaceType,
            device.deviceType,
            device.insertTime.format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")),
            device.lastActivity.format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
        };
        tableModel.addRow(rowData);
    }
}

    /**
     * 在日志区域添加消息
     */
    private void logMessage(String message) {
        logArea.append(message + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    /**
     * 写入日志到文件
     */
    // 修改processMonitor.py文件中的日志记录格式
    // 在writeToLogs方法中添加中文字段名
    private void writeToLogs(String message, boolean deviceLog, boolean processLog) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        if (deviceLog && deviceLogWriter != null) {
            deviceLogWriter.println("[" + timestamp + "] " + message);
        }
        if (processLog && processLogWriter != null) {
            processLogWriter.println("[" + timestamp + "] " + message);
        }
    }
    
    /**
     * 写入详细进程信息到单独的文本文件
     */
    private void writeToProcessTxtLog(String message) {
        try {
            String logDirName = config.getProperty("logDirectory", "logs");
            File logFile = new File(logDirName, "process-details.txt");
            PrintWriter writer = new PrintWriter(new FileWriter(logFile, true), true);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            writer.println("[" + timestamp + "] " + message);
            writer.close();
        } catch (IOException e) {
            logMessage("写入进程详细日志时出错: " + e.getMessage());
        }
    }


    /**
     * 导出日志功能
     */
    private void exportLogs() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("选择导出位置和格式");
        FileNameExtensionFilter txtFilter = new FileNameExtensionFilter("文本文件 (*.txt)", "txt");
        FileNameExtensionFilter csvFilter = new FileNameExtensionFilter("CSV文件 (*.csv)", "csv");
        FileNameExtensionFilter jsonFilter = new FileNameExtensionFilter("JSON文件 (*.json)", "json");
        fileChooser.addChoosableFileFilter(txtFilter);
        fileChooser.addChoosableFileFilter(csvFilter);
        fileChooser.addChoosableFileFilter(jsonFilter);
        fileChooser.setFileFilter(txtFilter);
        fileChooser.setSelectedFile(new File("usb_monitor_logs_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))));

        int userSelection = fileChooser.showSaveDialog(this);
        if (userSelection == JFileChooser.APPROVE_OPTION) {
            File fileToSave = fileChooser.getSelectedFile();
            String fileName = fileToSave.getAbsolutePath();

            if (fileChooser.getFileFilter() == csvFilter && !fileName.endsWith(".csv")) fileName += ".csv";
            else if (fileChooser.getFileFilter() == jsonFilter && !fileName.endsWith(".json")) fileName += ".json";
            else if (fileChooser.getFileFilter() == txtFilter && !fileName.endsWith(".txt")) fileName += ".txt";

            try {
                if (fileName.endsWith(".csv")) exportAsCsv(new File(fileName));
                else if (fileName.endsWith(".json")) exportAsJson(new File(fileName));
                else exportAsTxt(new File(fileName));

                logMessage("日志已导出到: " + fileName);
                JOptionPane.showMessageDialog(this, "日志导出成功！", "导出完成", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                logMessage("导出日志失败: " + e.getMessage());
                JOptionPane.showMessageDialog(this, "导出日志失败: " + e.getMessage(), "导出失败", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /**
     * 导出为TXT格式
     */
    private void exportAsTxt(File file) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            writer.println("USB设备监控日志");
            writer.println("导出时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            writer.println("=" .repeat(50));
            writer.print(logArea.getText());
        }
    }

    /**
     * 导出为CSV格式
     */
    // 修改exportAsCsv方法以支持中文字段名
private void exportAsCsv(File file) throws IOException {
    try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
        // 添加中文字段名
        writer.println("驱动器,卷标,序列号,制造商,型号,总容量(GB),可用空间(GB),文件系统,接口类型,插入时间");
        for (Map.Entry<String, DeviceInfo> entry : deviceInfoMap.entrySet()) {
            DeviceInfo info = entry.getValue();
            String row = String.format("%s,%s,%s,%s,%s,%.2f,%.2f,%s,%s,%s",
                info.drivePath,
                info.volumeLabel,
                info.serialNumber,
                info.manufacturer,
                info.model,
                info.totalSpace / (1024.0 * 1024.0 * 1024.0),
                info.freeSpace / (1024.0 * 1024.0 * 1024.0),
                info.fileSystem,
                info.interfaceType,
                info.insertTime.format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
            );
            writer.println(row);
        }
    }
}

    /**
     * 导出为JSON格式
     */
    private void exportAsJson(File file) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            List<Map<String, Object>> devices = new ArrayList<>();
            for (Map.Entry<String, DeviceInfo> entry : deviceInfoMap.entrySet()) {
                DeviceInfo info = entry.getValue();
                Map<String, Object> deviceMap = new HashMap<>();
                deviceMap.put("drivePath", info.drivePath);
                deviceMap.put("volumeLabel", info.volumeLabel);
                deviceMap.put("serialNumber", info.serialNumber);
                deviceMap.put("manufacturer", info.manufacturer);
                deviceMap.put("model", info.model);
                deviceMap.put("totalSpaceGB", info.totalSpace / (1024.0 * 1024.0 * 1024.0));
                deviceMap.put("freeSpaceGB", info.freeSpace / (1024.0 * 1024.0 * 1024.0));
                deviceMap.put("fileSystem", info.fileSystem);
                deviceMap.put("interfaceType", info.interfaceType);
                deviceMap.put("insertTime", info.insertTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                devices.add(deviceMap);
            }
            writer.println(formatAsJson(devices));
        }
    }

    /**
     * 手动JSON格式化
     */
    private String formatAsJson(List<Map<String, Object>> devices) {
        StringBuilder json = new StringBuilder();
        json.append("[\n");
        for (int i = 0; i < devices.size(); i++) {
            Map<String, Object> device = devices.get(i);
            json.append("  {\n");
            int count = 0;
            for (Map.Entry<String, Object> entry : device.entrySet()) {
                if (count > 0) json.append(",\n");
                String key = entry.getKey();
                Object value = entry.getValue();
                json.append("    \"").append(key).append("\": ");
                if (value instanceof String || value instanceof LocalDateTime) {
                    json.append("\"").append(value.toString()).append("\"");
                } else {
                    json.append(value.toString());
                }
                count++;
            }
            json.append("\n  }");
            if (i < devices.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("]");
        return json.toString();
    }

    /**
     * 获取鼠标和键盘设备信息
     */
    private java.util.List<DeviceInfo> getInputDevices() {
        java.util.List<DeviceInfo> inputDevices = new java.util.ArrayList<>();
        try { 
            ProcessBuilder keyboardProcessBuilder = new ProcessBuilder("wmic", "path", "Win32_Keyboard", "get", "Description,Name,DeviceID");
            Process keyboardProcess = keyboardProcessBuilder.start();
            Scanner keyboardScanner = new Scanner(keyboardProcess.getInputStream());
            while (keyboardScanner.hasNextLine()) {
                String line = keyboardScanner.nextLine().trim();
                if (line.contains("Keyboard") || line.contains("键盘")) {
                    DeviceInfo info = new DeviceInfo();
                    info.deviceType = "键盘";
                    info.model = line;
                    info.manufacturer = "未知";
                    info.interfaceType = "USB";
                    info.insertTime = LocalDateTime.now();
                    info.lastActivity = LocalDateTime.now();
                    inputDevices.add(info);
                }
            }
            keyboardScanner.close();
            keyboardProcess.waitFor();

            ProcessBuilder mouseProcessBuilder = new ProcessBuilder("wmic", "path", "Win32_PointingDevice", "get", "Description,Name,DeviceID");
            Process mouseProcess = mouseProcessBuilder.start();
            Scanner mouseScanner = new Scanner(mouseProcess.getInputStream());
            while (mouseScanner.hasNextLine()) {
                String line = mouseScanner.nextLine().trim();
                if (line.contains("Mouse") || line.contains("鼠标")) {
                    DeviceInfo info = new DeviceInfo();
                    info.deviceType = "鼠标";
                    info.model = line;
                    info.manufacturer = "未知";
                    info.interfaceType = "USB";
                    info.insertTime = LocalDateTime.now();
                    info.lastActivity = LocalDateTime.now();
                    inputDevices.add(info);
                }
            }
            mouseScanner.close();
            mouseProcess.waitFor();
        } catch (Exception e) {
            logMessage("获取输入设备信息时出错: " + e.getMessage());
        }
        return inputDevices;
    }

    /**
     * 检查鼠标和键盘设备
     */
    private void checkInputDevices() {
        List<DeviceInfo> inputDevices = getInputDevices();
        for (DeviceInfo device : inputDevices) {
            String message = String.format("检测到输入设备 - 类型: %s, 描述: %s", device.deviceType, device.model);
            logMessage(message);
            writeToLogs(message, true, true);
        }
    }

    /**
     * 启动进程监控
     */
    private void startProcessMonitoring() {
        int interval = Integer.parseInt(config.getProperty("processCheckInterval", "5000"));
        processTimer = new Timer(interval, e -> checkProcesses());
        processTimer.start();
    }

    /**
     * 检查系统进程
     */
    private void checkProcesses() {
        try {
            ProcessBuilder pb = new ProcessBuilder("tasklist", "/FO", "CSV", "/NH");
            Process process = pb.start();
            Scanner scanner = new Scanner(process.getInputStream());
            StringBuilder output = new StringBuilder();
            while (scanner.hasNextLine()) {
                output.append(scanner.nextLine()).append("\n");
            }
            scanner.close();
            process.waitFor();

            String[] lines = output.toString().split("\n");
            // 使用Map存储进程名称和PID的映射关系，以便在日志中正确显示
            Map<String, String> currentProcessMap = new HashMap<>();
            
            // 清空进程表格
            processTableModel.setRowCount(0);
            
            // 获取当前可移动驱动器列表
            Set<String> currentDrives = getRemovableDrives();
            
            // 标记是否检测到作弊程序
            boolean cheatEngineDetected = false;
            
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    String processName = parts[0].trim().replaceAll("\"", "");
                    String pid = parts[1].trim().replaceAll("\"", ""); // PID在第二列
                    
                    // 检查是否为作弊程序
                    if (isCheatProcess(processName)) {
                        cheatEngineDetected = true;
                        
                        // 获取进程详细信息
                        String processDetails = getCheatProcessDetails(pid, processName);
                        showCheatAlert(processDetails);
                        logCheatDetection(processDetails);
                    }
                    
                    // 检查是否为系统进程
                    if (systemProcesses.contains(processName.toLowerCase())) {
                        // 如果是系统进程，则记录到隐藏进程日志中而不显示在GUI上
                        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                        String logMessage = String.format("隐藏系统进程: %s (PID: %s)", processName, pid);
                        if (hiddenProcessLogWriter != null) {
                            hiddenProcessLogWriter.println(String.format("[%s] %s", timestamp, logMessage));
                        }
                        // 同时记录到常规进程日志中
                        writeToLogs(logMessage, false, true);
                        continue; // 跳过该进程，不显示在GUI中
                    }
                    
                    currentProcessMap.put(processName, pid);
                    
                    // 添加到进程表格中
                    Object[] rowData = {
                        processName + " (PID: " + pid + ")",  // 进程名称+PID
                        "运行中",      // 状态
                        "N/A",        // CPU使用率
                        "N/A",        // 内存使用率
                        "N/A"         // 启动时间
                    };
                    processTableModel.addRow(rowData);
                }
            }

            // 如果之前检测到作弊程序，但现在没有了，则隐藏警告窗口
            if (cheatDetected && !cheatEngineDetected) {
                hideCheatAlert();
            }
            
            // 检查新进程
            for (Map.Entry<String, String> entry : currentProcessMap.entrySet()) {
                String processName = entry.getKey();
                String pid = entry.getValue();
                if (!previousProcesses.contains(processName)) {
                    String msg = String.format("新进程启动: %s (PID: %s)", processName, pid);
                    logMessage(msg);
                    writeToLogs(msg, false, true);
                    writeToProcessTxtLog("检测到新进程启动: " + processName + " (PID: " + pid + ")");
                    
                    // 检查进程是否正在访问可移动驱动器
                    checkProcessAccessingDrives(processName, pid, currentDrives);
                }
            }

            // 检查终止进程
            for (String proc : previousProcesses) {
                boolean found = false;
                for (String currentProc : currentProcessMap.keySet()) {
                    if (currentProc.equals(proc)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    String msg = String.format("进程已终止: %s", proc);
                    logMessage(msg);
                    writeToLogs(msg, false, true);
                    writeToProcessTxtLog("检测到进程终止: " + proc);
                }
            }

            // 更新previousProcesses为当前进程名称集合
            previousProcesses = new HashSet<>(currentProcessMap.keySet());

        } catch (Exception e) {
            logMessage("检查进程时出错: " + e.getMessage());
            writeToLogs("检查进程时出错: " + e.getMessage(), false, true);
        }
    }

    /**
     * 检查是否为作弊程序
     */
    private boolean isCheatProcess(String processName) {
        // 如果警报已被解除，则不再检测作弊程序
        if (cheatAlertDismissed) {
            return false;
        }
        
        String lowerProcessName = processName.toLowerCase();
        return lowerProcessName.contains("cheatengine") || 
               lowerProcessName.contains("cheat engine") ||
               lowerProcessName.equals("cheatengine.exe");
    }
    
    /**
     * 获取作弊进程详细信息
     */
    private String getCheatProcessDetails(String pid, String processName) {
        StringBuilder details = new StringBuilder();
        LocalDateTime detectionTime = LocalDateTime.now();
        String timestamp = detectionTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        
        details.append("检测时间: ").append(timestamp).append("\n");
        details.append("进程名称: ").append(processName).append("\n");
        details.append("进程ID: ").append(pid).append("\n");
        
        try {
            // 获取进程可执行文件路径
            ProcessBuilder pb = new ProcessBuilder("wmic", "process", "where", "processid=" + pid, "get", "executablepath,name,creationdate", "/format:value");
            Process process = pb.start();
            Scanner scanner = new Scanner(process.getInputStream());
            
            String exePath = "未知";
            String creationDate = "未知";
            
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.startsWith("ExecutablePath=")) {
                    exePath = line.substring("ExecutablePath=".length());
                } else if (line.startsWith("Name=")) {
                    // 这个我们已经有了
                } else if (line.startsWith("CreationDate=")) {
                    creationDate = line.substring("CreationDate=".length());
                    // 格式化时间
                    if (creationDate.length() >= 14) {
                        try {
                            // WMIC时间格式: yyyymmddHHMMSS
                            String formattedDate = creationDate.substring(0, 4) + "-" + 
                                                 creationDate.substring(4, 6) + "-" + 
                                                 creationDate.substring(6, 8) + " " + 
                                                 creationDate.substring(8, 10) + ":" + 
                                                 creationDate.substring(10, 12) + ":" + 
                                                 creationDate.substring(12, 14);
                            creationDate = formattedDate;
                        } catch (Exception e) {
                            // 保持原始值
                        }
                    }
                }
            }
            
            scanner.close();
            process.waitFor();
            
            details.append("可执行文件路径: ").append(exePath).append("\n");
            details.append("启动时间: ").append(creationDate).append("\n");
            
            // 计算运行时间
            if (!"未知".equals(creationDate) && !"未知".equals(creationDate)) {
                try {
                    LocalDateTime startTime = LocalDateTime.parse(creationDate, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    long hours = java.time.Duration.between(startTime, detectionTime).toHours();
                    long minutes = java.time.Duration.between(startTime, detectionTime).toMinutes() % 60;
                    details.append("运行时间: ").append(hours).append("小时 ").append(minutes).append("分钟\n");
                } catch (Exception e) {
                    details.append("运行时间: 无法计算\n");
                }
            } else {
                details.append("运行时间: 未知\n");
            }
            
        } catch (Exception e) {
            details.append("无法获取详细信息: ").append(e.getMessage()).append("\n");
        }
        
        return details.toString();
    }

    /**
     * 显示作弊警告
     */
    private void showCheatAlert(String processDetails) {
        // 只有在未解除警报的情况下才显示警告
        if (!cheatDetected && !cheatAlertDismissed) {
            cheatDetected = true;
            
            // 更新日志
            logMessage("检测到作弊程序: " + processDetails);
            
            // 显示全屏警告窗口
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice gd = ge.getDefaultScreenDevice();
            
            // 设置窗口为全屏并确保可见
            cheatAlertFrame.setVisible(true);
            if (gd.isFullScreenSupported()) {
                gd.setFullScreenWindow(cheatAlertFrame);
            } else {
                cheatAlertFrame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            }
            
            // 确保窗口在最前面并获得焦点
            cheatAlertFrame.toFront();
            cheatAlertFrame.requestFocus();
            cheatAlertFrame.repaint();
            
            // 寻找密码输入框并请求焦点
            SwingUtilities.invokeLater(() -> {
                findAndFocusPasswordField(cheatAlertFrame);
                
                // 再次尝试设置为全屏（某些系统可能需要延迟执行）
                if (gd.isFullScreenSupported()) {
                    gd.setFullScreenWindow(cheatAlertFrame);
                }
            });
        }
    }
    
    /**
     * 查找并聚焦密码输入框
     */
    private void findAndFocusPasswordField(Container container) {
        for (Component component : container.getComponents()) {
            if (component instanceof JPasswordField) {
                JPasswordField passwordField = (JPasswordField) component;
                passwordField.requestFocusInWindow();
                return;
            } else if (component instanceof Container) {
                findAndFocusPasswordField((Container) component);
            }
        }
    }
    

    /**
     * 隐藏作弊警告
     */
    private void hideCheatAlert() {
        if (cheatDetected && !cheatAlertDismissed) {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice gd = ge.getDefaultScreenDevice();
            gd.setFullScreenWindow(null);
            cheatAlertFrame.setVisible(false);
        }
    }
    
/**
 * 清除作弊状态（由用户输入正确密码后调用）
 */
private void clearCheatStatus() {
    if (cheatDetected) {
        cheatDetected = false;
        cheatAlertDismissed = true; // 标记警报已解除
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice gd = ge.getDefaultScreenDevice();
        gd.setFullScreenWindow(null);
        cheatAlertFrame.setVisible(false);
    }
}

    
    /**
     * 记录作弊检测到日志
     */
    private void logCheatDetection(String details) {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String logEntry = "=== 作弊程序检测 ===\n" + 
                             details + 
                             "==================\n";
            
            if (cheatLogWriter != null) {
                cheatLogWriter.println("[" + timestamp + "] " + logEntry);
                cheatLogWriter.flush();
            }
            
            // 同时记录到其他日志中
            writeToLogs("检测到作弊程序: " + details.replaceAll("\n", " "), true, true);
        } catch (Exception e) {
            System.err.println("记录作弊检测日志时出错: " + e.getMessage());
        }
    }
    
    /**
     * 检查进程是否正在访问可移动驱动器
     */
    private void checkProcessAccessingDrives(String processName, String pid, Set<String> currentDrives) {
        try {
            // 使用handle.exe工具检查进程打开的文件句柄
            ProcessBuilder pb = new ProcessBuilder("handle.exe", "-p", pid);
            Process process = pb.start();
            Scanner scanner = new Scanner(process.getInputStream());
            
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                // 检查行是否包含驱动器路径
                for (String drive : currentDrives) {
                    if (line.contains(drive)) {
                        String message = String.format(
                            "进程 '%s' (PID: %s) 正在访问USB设备 (%s)", 
                            processName, pid, drive);
                        logMessage(message);
                        writeToLogs(message, true, true);
                        break;
                    }
                }
            }
            
            scanner.close();
            process.waitFor();
        } catch (Exception e) {
            
            // 替代方案：尝试通过查询进程的命令行参数判断
            try {
                ProcessBuilder pb = new ProcessBuilder("wmic", "process", "where", "processid=" + pid, "get", "executablepath", "/format:value");
                Process process = pb.start();
                Scanner scanner = new Scanner(process.getInputStream());
                
                if (scanner.hasNextLine()) {
                    String pathLine = scanner.nextLine();
                    if (pathLine.startsWith("ExecutablePath=")) {
                        String exePath = pathLine.substring("ExecutablePath=".length()).trim();
                        
                        // 检查可执行文件路径是否在可移动驱动器上
                        for (String drive : currentDrives) {
                            if (exePath.startsWith(drive)) {
                                String message = String.format(
                                    "进程 '%s' (PID: %s) 正在访问USB设备 (%s)，可执行文件路径：%s", 
                                    processName, pid, drive, exePath);
                                logMessage(message);
                                writeToLogs(message, true, true);
                                break;
                            }
                        }
                    }
                }
                
                scanner.close();
                process.waitFor();
            } catch (Exception ex) {
                // 忽略错误
            }
        }
    }
    
    /**
     * 获取可移动磁盘驱动器
     */
    private Set<String> getRemovableDrives() {
        Set<String> drives = new HashSet<>();
        try {
            // 使用WMIC命令获取磁盘驱动器信息
            ProcessBuilder pb = new ProcessBuilder("wmic", "logicaldisk", "where", "drivetype=2", "get", "deviceid", "/format:value");
            Process process = pb.start();
            Scanner scanner = new Scanner(process.getInputStream());
            
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.startsWith("DeviceID=")) {
                    String drive = line.substring("DeviceID=".length()).trim() + "\\";
                    drives.add(drive);
                }
            }
            
            scanner.close();
            process.waitFor();
        } catch (Exception e) {
            logMessage("获取可移动驱动器信息时出错: " + e.getMessage());
        }
        
        return drives;
    }

    /**
     * 显示系统托盘通知
     */
    private void showTrayNotification(String caption, String message) {
        if (!Boolean.parseBoolean(config.getProperty("enableTrayNotifications", "true"))) return;
        if (SystemTray.isSupported()) {
            SystemTray tray = SystemTray.getSystemTray();
            TrayIcon[] icons = tray.getTrayIcons();
            if (icons.length > 0) {
                icons[0].displayMessage(caption, message, TrayIcon.MessageType.INFO);
            }
        }
    }

    /**
     * 设备信息类
     */
    private static class DeviceInfo {
        String drivePath;
        String volumeLabel;
        String serialNumber;
        String model;
        String manufacturer;
        String interfaceType;
        String deviceType;
        long totalSpace;
        long freeSpace;
        long usableSpace;
        String fileSystem;
        LocalDateTime insertTime;
        LocalDateTime lastActivity;
    }

// 在startHttpServer方法中注册新的处理器
private void startHttpServer() {
    try {
        httpServer = HttpServer.create(new InetSocketAddress(8080), 0);
        
        // 现有的处理器
        httpServer.createContext("/api/stats", new StatsHandler());
        httpServer.createContext("/api/devices", new DevicesHandler());
        httpServer.createContext("/api/processes", new ProcessesHandler());
        httpServer.createContext("/api/logs", new LogsHandler());
        httpServer.createContext("/", new StaticFileHandler());
        
        // 新增的飞书认证和管理员处理器
        httpServer.createContext("/api/auth/feishu", new FeishuAuthHandler());
        httpServer.createContext("/api/admin/check", new AdminCheckHandler());
        httpServer.createContext("/api/admin/restart", new AdminActionHandler());
        httpServer.createContext("/api/admin/clear-logs", new AdminActionHandler());
        httpServer.createContext("/api/admin/export-all", new AdminActionHandler());
        httpServer.createContext("/api/admin/system-info", new AdminActionHandler());
        
        httpServer.setExecutor(Executors.newFixedThreadPool(10));
        httpServer.start();
        
        logMessage("HTTP服务器已启动，监听端口8080");
        writeToLogs("HTTP服务器已启动，监听端口8080", true, true);
    } catch (Exception e) {
        logMessage("启动HTTP服务器失败: " + e.getMessage());
        e.printStackTrace();
    }
}
    
    /**
     * 停止HTTP服务器
     */
    private void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop(0);
            logMessage("HTTP服务器已停止");
            writeToLogs("HTTP服务器已停止", true, true);
        }
    }
    
    /**
     * 获取UsbMonitor实例
     * @return UsbMonitor实例
     */
    public static PACC getInstance() {
        return instance;
    }
    
    /**
     * 获取设备信息映射
     * @return 设备信息映射
     */
    public Map<String, DeviceInfo> getDeviceInfoMap() {
        return deviceInfoMap;
    }
    
    /**
     * 获取进程信息
     * @return 进程信息
     */
    public Set<String> getProcessInfo() {
        return previousProcesses;
    }
    
    /**
     * 获取启动时间
     */
    public String getStartTime() {
        // 这里可以返回实际的启动时间
        return LocalDateTime.now().toString();
    }
    
    /**
     * 统计信息处理器
     */
    static class StatsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod())) {
                PACC monitor = PACC.getInstance();
                Map<String, Object> stats = new HashMap<>();
                
                stats.put("deviceCount", monitor != null ? monitor.getDeviceInfoMap().size() : 0);
                stats.put("processCount", monitor != null ? monitor.getProcessInfo().size() : 0);
                stats.put("startTime", monitor != null ? monitor.getStartTime() : LocalDateTime.now().toString());
                stats.put("status", "running");
                
                String response = toJson(stats);
                sendResponse(exchange, 200, response);
            } else {
                sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            }
        }
    }
    
    /**
     * 设备信息处理器
     */
    static class DevicesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod())) {
                PACC usbMonitor = PACC.getInstance();
                List<Map<String, Object>> devices = new ArrayList<>();
                
                if (usbMonitor != null) {
                    Map<String, DeviceInfo> deviceInfoMap = usbMonitor.getDeviceInfoMap();
                    for (Map.Entry<String, DeviceInfo> entry : deviceInfoMap.entrySet()) {
                        DeviceInfo deviceInfo = entry.getValue();
                        Map<String, Object> deviceData = new HashMap<>();
                        deviceData.put("drivePath", deviceInfo.drivePath != null ? deviceInfo.drivePath : "N/A");
                        deviceData.put("volumeLabel", deviceInfo.volumeLabel != null ? deviceInfo.volumeLabel : "N/A");
                        deviceData.put("serialNumber", deviceInfo.serialNumber != null ? deviceInfo.serialNumber : "N/A");
                        deviceData.put("manufacturer", deviceInfo.manufacturer != null ? deviceInfo.manufacturer : "N/A");
                        deviceData.put("model", deviceInfo.model != null ? deviceInfo.model : "N/A");
                        deviceData.put("totalSpace", deviceInfo.totalSpace);
                        deviceData.put("freeSpace", deviceInfo.freeSpace);
                        deviceData.put("fileSystem", deviceInfo.fileSystem != null ? deviceInfo.fileSystem : "N/A");
                        deviceData.put("insertTime", deviceInfo.insertTime != null ? deviceInfo.insertTime.toString() : "N/A");
                        deviceData.put("lastActivity", deviceInfo.lastActivity != null ? deviceInfo.lastActivity.toString() : "N/A");
                        devices.add(deviceData);
                    }
                }
                
                String response = PACC.toJson(devices);
                sendResponse(exchange, 200, response);
            } else {
                sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            }
        }
    }

    /**
     * 进程信息处理器
     */
    static class ProcessesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod())) {
                PACC usbMonitor = PACC.getInstance();
                List<Map<String, Object>> processes = new ArrayList<>();
                
                if (usbMonitor != null) {
                    // 这里应该返回实际的进程信息
                    // 由于数据结构不同，我们暂时返回示例数据
                    Set<String> processSet = usbMonitor.getProcessInfo();
                    for (String processName : processSet) {
                        Map<String, Object> processData = new HashMap<>();
                        processData.put("name", processName);
                        processData.put("pid", "N/A");
                        processData.put("status", "running");
                        processData.put("cpuUsage", "N/A");
                        processData.put("memoryUsage", "N/A");
                        processData.put("startTime", "N/A");
                        processes.add(processData);
                    }
                }
                
                String response = PACC.toJson(processes);
                sendResponse(exchange, 200, response);
            } else {
                sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            }
        }
    }
    
    /**
     * 日志信息处理器
     */
    static class LogsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod())) {
                // 返回最新的日志条目
                List<String> logs = new ArrayList<>();
                logs.add("系统运行正常");
                logs.add("监控服务已启动");
                logs.add("HTTP API服务已启用");
                
                String response =toJson(logs);
                sendResponse(exchange, 200, response);
            } else {
                sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            }
        }
    }
    
    /**
     * 静态文件处理器（用于提供前端页面）
     */
    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            
            // 如果请求根路径，返回index.html
            if ("/".equals(path) || "".equals(path)) {
                path = "/index.html";
            }
            
            // 确定文件路径
            String filePath = "." + path; // 假设静态文件在当前目录
            
            try {
                File file = new File(filePath);
                if (file.exists() && !file.isDirectory()) {
                    // 读取文件内容
                    byte[] content = Files.readAllBytes(file.toPath());
                    
                    // 设置内容类型
                    String contentType = getContentType(filePath);
                    exchange.getResponseHeaders().set("Content-Type", contentType);
                    
                    // 发送响应
                    exchange.sendResponseHeaders(200, content.length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(content);
                    os.close();
                } else {
                    // 文件不存在，返回404
                    String response = "{\"error\":\"File not found\"}";
                    sendResponse(exchange, 404, response);
                }
            } catch (Exception e) {
                String response = "{\"error\":\"Internal server error\"}";
                sendResponse(exchange, 500, response);
            }
        }
        
        /**
         * 根据文件扩展名获取内容类型
         */
        private String getContentType(String filePath) {
            if (filePath.endsWith(".html")) return "text/html; charset=utf-8";
            if (filePath.endsWith(".css")) return "text/css; charset=utf-8";
            if (filePath.endsWith(".js")) return "application/javascript; charset=utf-8";
            if (filePath.endsWith(".json")) return "application/json; charset=utf-8";
            return "text/plain; charset=utf-8";
        }
    }

    /**
     * 初始化作弊警告窗口
     */
    private void initializeCheatAlertWindow() {
        cheatAlertFrame = new JFrame("作弊检测警告") {
            // 重写setVisible方法确保窗口始终置顶
            @Override
            public void setVisible(boolean visible) {
                super.setVisible(visible);
                if (visible) {
                    toFront();
                    requestFocus();
                }
            }
        };
        
        cheatAlertFrame.setUndecorated(true);
        cheatAlertFrame.setAlwaysOnTop(true);
        cheatAlertFrame.setBackground(Color.RED);
        cheatAlertFrame.setAutoRequestFocus(true);
        
        // 确保窗口不会被关闭按钮关闭
        cheatAlertFrame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        
        // 创建警告面板
        JPanel alertPanel = new JPanel(new BorderLayout());
        alertPanel.setBackground(Color.RED);
        
        // 创建警告标签
        JLabel alertLabel = new JLabel("<html><div style='text-align: center;'>" +
                "<span style='font-size: 72px; font-weight: bold;'>PACC</span><br/>" +
                "<span style='font-size: 48px;'>发现作弊者</span>" +
                "</div></html>", SwingConstants.CENTER);
        alertLabel.setFont(new Font("微软雅黑", Font.BOLD, 48));
        alertLabel.setForeground(Color.WHITE);
        alertLabel.setVerticalAlignment(SwingConstants.CENTER);
        
        alertPanel.add(alertLabel, BorderLayout.CENTER);
        
        // 创建密码输入面板
        JPanel passwordPanel = new JPanel(new GridBagLayout());
        passwordPanel.setBackground(Color.RED);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 10, 10, 10);
        
        gbc.gridx = 0; gbc.gridy = 0;
        passwordPanel.add(new JLabel("请输入密码以解除警报:"), gbc);
        
        gbc.gridx = 0; gbc.gridy = 1;
        JPasswordField passwordField = new JPasswordField(15);
        passwordField.setFont(new Font("微软雅黑", Font.PLAIN, 18));
        passwordField.addActionListener(e -> verifyPasswordAndClose(passwordField.getPassword()));
        passwordPanel.add(passwordField, gbc);
        
        gbc.gridx = 0; gbc.gridy = 2;
        JButton confirmButton = new JButton("确认");
        confirmButton.setFont(new Font("微软雅黑", Font.PLAIN, 18));
        confirmButton.addActionListener(e -> verifyPasswordAndClose(passwordField.getPassword()));
        passwordPanel.add(confirmButton, gbc);
        
        gbc.gridx = 0; gbc.gridy = 3;
        JLabel errorLabel = new JLabel(" ");
        errorLabel.setForeground(Color.YELLOW);
        errorLabel.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        passwordPanel.add(errorLabel, gbc);
        
        alertPanel.add(passwordPanel, BorderLayout.SOUTH);
        
        cheatAlertFrame.add(alertPanel);
        cheatAlertFrame.setSize(800, 600);
        cheatAlertFrame.setLocationRelativeTo(null);
        
        // 添加窗口焦点监听器，确保窗口保持焦点
        cheatAlertFrame.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent e) {
                if (cheatDetected && !cheatAlertDismissed) {
                    // 当失去焦点时重新获取焦点
                    SwingUtilities.invokeLater(() -> {
                        if (cheatDetected && !cheatAlertDismissed) { // 仍然处于作弊检测状态且未解除警报
                            cheatAlertFrame.setVisible(true);
                            cheatAlertFrame.toFront();
                            cheatAlertFrame.requestFocus();
                        }
                    });
                }
            }
        });
        
        // 添加窗口状态监听器，防止窗口被最小化
        cheatAlertFrame.addWindowStateListener(e -> {
            if ((cheatDetected && !cheatAlertDismissed) && (e.getNewState() == Frame.ICONIFIED || 
                                 (e.getNewState() & Frame.ICONIFIED) != 0)) {
                SwingUtilities.invokeLater(() -> {
                    if ((cheatDetected && !cheatAlertDismissed)) {
                        cheatAlertFrame.setExtendedState(Frame.NORMAL);
                        cheatAlertFrame.toFront();
                        cheatAlertFrame.requestFocus();
                    }
                });
            }
        });
    }

    /**
     * 验证密码并关闭警告窗口
     */
    private void verifyPasswordAndClose(char[] inputPassword) {
        String password = new String(inputPassword);
        if (CHEAT_ALERT_PASSWORD.equals(password)) {
            // 密码正确，清除作弊状态
            clearCheatStatus();
        } else {
            // 密码错误，显示错误信息
            // 注意：这里我们需要找到错误标签来显示消息
            Component[] components = cheatAlertFrame.getContentPane().getComponents();
            for (Component component : components) {
                if (component instanceof JPanel) {
                    Component[] panelComponents = ((JPanel) component).getComponents();
                    for (Component panelComponent : panelComponents) {
                        if (panelComponent instanceof JPanel) {
                            Component[] passwordPanelComponents = ((JPanel) panelComponent).getComponents();
                            for (Component passwordComponent : passwordPanelComponents) {
                                if (passwordComponent instanceof JLabel && 
                                    ((JLabel) passwordComponent).getText().equals(" ")) {
                                    ((JLabel) passwordComponent).setText("密码错误，请重试");
                                    ((JLabel) passwordComponent).setForeground(Color.YELLOW);
                                    // 清空密码输入框
                                    for (Component clearComponent : passwordPanelComponents) {
                                        if (clearComponent instanceof JPasswordField) {
                                            ((JPasswordField) clearComponent).setText("");
                                        }
                                    }
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 初始化鼠标点击监控
     */
    private void initializeMouseClickMonitoring() {
        // 创建一个定时器，用于重置鼠标点击计数
        mouseClickResetTimer = new Timer(MOUSE_CLICK_TIME_WINDOW, e -> {
            // 重置鼠标点击计数
            leftClickCount = 0;
            rightClickCount = 0;
        });
        mouseClickResetTimer.setRepeats(false); // 只执行一次
        
        // 添加全局鼠标监听器
        Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
            @Override
            public void eventDispatched(AWTEvent event) {
                if (event instanceof MouseEvent) {
                    MouseEvent mouseEvent = (MouseEvent) event;
                    if (mouseEvent.getID() == MouseEvent.MOUSE_PRESSED) {
                        handleMouseClick(mouseEvent);
                    }
                }
            }
        }, AWTEvent.MOUSE_EVENT_MASK);
    }
    
    /**
     * 处理鼠标点击事件
     */
    private void handleMouseClick(MouseEvent e) {
        long currentTime = System.currentTimeMillis();
        
        if (e.getButton() == MouseEvent.BUTTON1) { // 左键
            // 如果距离上次点击时间超过了时间窗口，则重置计数
            if (currentTime - lastLeftClickTime > MOUSE_CLICK_TIME_WINDOW) {
                leftClickCount = 0;
            }
            
            leftClickCount++;
            lastLeftClickTime = currentTime;
            
            // 检查是否超过阈值
            if (leftClickCount >= MOUSE_CLICK_THRESHOLD) {
                triggerMouseCheatAlert("左键");
                leftClickCount = 0; // 重置计数
            }
        } else if (e.getButton() == MouseEvent.BUTTON3) { // 右键
            // 如果距离上次点击时间超过了时间窗口，则重置计数
            if (currentTime - lastRightClickTime > MOUSE_CLICK_TIME_WINDOW) {
                rightClickCount = 0;
            }
            
            rightClickCount++;
            lastRightClickTime = currentTime;
            
            // 检查是否超过阈值
            if (rightClickCount >= MOUSE_CLICK_THRESHOLD) {
                triggerMouseCheatAlert("右键");
                rightClickCount = 0; // 重置计数
            }
        }
        
        // 重启计时器
        if (mouseClickResetTimer.isRunning()) {
            mouseClickResetTimer.restart();
        } else {
            mouseClickResetTimer.start();
        }
    }
    
    /**
     * 触发鼠标作弊警报
     */
    private void triggerMouseCheatAlert(String buttonType) {
        // 如果警报已被解除，则不再触发
        if (cheatAlertDismissed) {
            return;
        }
        
        String cheatDetails = String.format(
            "检测到异常鼠标操作 - %s连续点击次数: %d次 (阈值: %d次, 时间窗口: %d秒)",
            buttonType, 
            MOUSE_CLICK_THRESHOLD, 
            MOUSE_CLICK_THRESHOLD, 
            MOUSE_CLICK_TIME_WINDOW / 1000
        );
        
        logMessage("检测到鼠标作弊行为: " + cheatDetails);
        showCheatAlert(cheatDetails);
        logCheatDetection(cheatDetails);
    }
    // 添加数据签名方法
private String signData(String data) {
    try {
        // 使用私钥对数据进行签名
        // 这里是示例代码，实际实现需要根据你的加密方案调整
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data.getBytes("UTF-8"));
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    } catch (Exception e) {
        logMessage("数据签名失败: " + e.getMessage());
        return "";
    }
}

// 修改HTTP处理器以包含签名
static class StatsRequestHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            PACC monitor = PACC.getInstance();
            Map<String, Object> stats = new HashMap<>();
            
            stats.put("deviceCount", monitor != null ? monitor.getDeviceInfoMap().size() : 0);
            stats.put("processCount", monitor != null ? monitor.getProcessInfo().size() : 0);
            stats.put("startTime", monitor != null ? monitor.getStartTime() : LocalDateTime.now().toString());
            stats.put("status", "running");
            
            // 添加数据签名
            String jsonData = toJson(stats);
            String signature = monitor.signData(jsonData);
            
            Map<String, Object> response = new HashMap<>();
            response.put("stats", stats);
            response.put("signature", signature);
            
            String responseJson = toJson(response);
            sendResponse(exchange, 200, responseJson);
        } else {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
        }
    }
}
// 在UsbMonitor类中添加新的HTTP处理器
/**
 * 飞书认证处理器
 */
static class FeishuAuthHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if ("POST".equals(exchange.getRequestMethod())) {
            try {
                // 读取请求体
                String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, Object> requestData = parseJson(requestBody);
                
                String code = (String) requestData.get("code");
                if (code == null || code.isEmpty()) {
                    sendResponse(exchange, 400, "{\"error\":\"缺少授权码\"}");
                    return;
                }
                
                // 使用授权码向飞书API换取访问令牌
                Map<String, Object> tokenResponse = exchangeCodeForToken(code);
                
                if (tokenResponse.containsKey("error")) {
                    sendResponse(exchange, 400, toJson(tokenResponse));
                    return;
                }
                
                // 获取用户信息
                String accessToken = (String) tokenResponse.get("access_token");
                Map<String, Object> userInfo = getUserInfo(accessToken);
                
                if (userInfo.containsKey("error")) {
                    sendResponse(exchange, 400, toJson(userInfo));
                    return;
                }
                
                // 检查用户是否为管理员（这里可以根据实际需求实现）
                String userId = (String) userInfo.get("user_id");
                boolean isAdmin = checkUserAdmin(userId);
                
                if (!isAdmin) {
                    sendResponse(exchange, 403, "{\"error\":\"权限不足\"}");
                    return;
                }
                
                // 生成自定义访问令牌（这里简化处理，实际应使用JWT等）
                String customToken = generateCustomToken(userId);
                String refreshToken = generateRefreshToken(userId);
                
                Map<String, Object> response = new HashMap<>();
                response.put("accessToken", customToken);
                response.put("refreshToken", refreshToken);
                response.put("user", userInfo);
                
                sendResponse(exchange, 200, toJson(response));
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"服务器内部错误: " + e.getMessage() + "\"}");
            }
        } else {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
        }
    }
    
    // 使用授权码换取访问令牌
    private Map<String, Object> exchangeCodeForToken(String code) {
        try {
            String appId = System.getenv("FEISHU_APP_ID");
            String appSecret = System.getenv("FEISHU_APP_SECRET");
            
            if (appSecret == null || appSecret.isEmpty()) {
                PACC monitor = new PACC();
                monitor.logMessage("未配置环境变量 FEISHU_APP_SECRET");
            } else {
                // 使用 appSecret 的相关逻辑
            }

            URI uri = URI.create("https://open.feishu.cn/open-apis/authen/v1/access_token");
            URL url = uri.toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            
            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("grant_type", "authorization_code");
            requestBody.put("code", code);
            requestBody.put("client_id", appId);        // 添加appId到请求体
            requestBody.put("client_secret", appSecret); // 添加appSecret到请求体
            
            String jsonInputString = toJson(requestBody);
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }
            
            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                    return parseJson(response.toString());
                }
            } else {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("error", "飞书API错误: " + responseCode);
                return errorResponse;
            }
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "网络错误: " + e.getMessage());
            return errorResponse;
        }
    }
    
    // 获取用户信息
    private Map<String, Object> getUserInfo(String accessToken) {
        try {
            URI uri = URI.create("https://open.feishu.cn/open-apis/user/v2/batch_get_id");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            
            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                    return parseJson(response.toString());
                }
            } else {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("error", "获取用户信息失败: " + responseCode);
                return errorResponse;
            }
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "网络错误: " + e.getMessage());
            return errorResponse;
        }
    }
    
    // 检查用户是否为管理员（示例实现）
    private boolean checkUserAdmin(String userId) {
        // 这里应该根据实际需求实现管理员检查逻辑
        // 例如查询数据库或配置文件
        Set<String> adminUsers = new HashSet<>(Arrays.asList(
            "admin_user_id_1",
            "admin_user_id_2"
        ));
        
        return adminUsers.contains(userId);
    }
    
    // 生成自定义访问令牌（示例实现）
    private String generateCustomToken(String userId) {
        // 实际项目中应使用JWT等标准令牌机制
        return Base64.getEncoder().encodeToString(
            (userId + ":" + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8)
        );
    }
    
    // 生成刷新令牌（示例实现）
    private String generateRefreshToken(String userId) {
        return Base64.getEncoder().encodeToString(
            (userId + ":refresh:" + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8)
        );
    }
}

/**
 * 管理员认证检查处理器
 */
static class AdminCheckHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            // 检查认证头
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                sendResponse(exchange, 401, "{\"error\":\"未提供认证信息\"}");
                return;
            }
            
            String token = authHeader.substring(7); // 移除 "Bearer " 前缀
            
            // 验证令牌（示例实现）
            if (validateToken(token)) {
                sendResponse(exchange, 200, "{\"isAdmin\":true}");
            } else {
                sendResponse(exchange, 403, "{\"error\":\"认证失败\"}");
            }
        } else {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
        }
    }
    
    // 验证令牌（示例实现）
    private boolean validateToken(String token) {
        try {
            String decoded = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":");
            if (parts.length < 2) return false;
            
            // 检查令牌是否过期（示例：1小时有效期）
            long timestamp = Long.parseLong(parts[parts.length - 1]);
            long now = System.currentTimeMillis();
            return (now - timestamp) < 3600000; // 1小时内有效
        } catch (Exception e) {
            return false;
        }
    }
}

/**
 * 管理员操作处理器
 */
static class AdminActionHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // 检查认证
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendResponse(exchange, 401, "{\"error\":\"未提供认证信息\"}");
            return;
        }
        
        String token = authHeader.substring(7);
        if (!validateToken(token)) {
            sendResponse(exchange, 403, "{\"error\":\"认证失败\"}");
            return;
        }
        
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        
        try {
            if ("/api/admin/restart".equals(path) && "POST".equals(method)) {
                handleRestart(exchange);
            } else if ("/api/admin/clear-logs".equals(path) && "POST".equals(method)) {
                handleClearLogs(exchange);
            } else if ("/api/admin/export-all".equals(path) && "GET".equals(method)) {
                handleExportAll(exchange);
            } else if ("/api/admin/system-info".equals(path) && "GET".equals(method)) {
                handleSystemInfo(exchange);
            } else {
                sendResponse(exchange, 404, "{\"error\":\"Endpoint not found\"}");
            }
        } catch (Exception e) {
            sendResponse(exchange, 500, "{\"error\":\"服务器内部错误: " + e.getMessage() + "\"}");
        }
    }
    
    private void handleRestart(HttpExchange exchange) throws IOException {
        // 重启监控服务的逻辑
        PACC monitor = PACC.getInstance();
        if (monitor != null) {
            // 停止当前监控
            if (monitor.timer != null) {
                monitor.timer.stop();
            }
            
            // 重新启动监控
            int interval = Integer.parseInt(monitor.config.getProperty("checkInterval", "3000"));
            monitor.timer = new Timer(interval, e -> monitor.checkUsbDevices());
            monitor.timer.start();
            
            monitor.logMessage("管理员重启了监控服务");
            sendResponse(exchange, 200, "{\"message\":\"监控服务已重启\"}");
        } else {
            sendResponse(exchange, 500, "{\"error\":\"无法获取监控实例\"}");
        }
    }
    
    private void handleClearLogs(HttpExchange exchange) throws IOException {
        // 清空日志的逻辑
        PACC monitor = PACC.getInstance();
        if (monitor != null) {
            // 清空日志区域
            monitor.logArea.setText("");
            
            // 记录操作日志
            monitor.logMessage("管理员清空了日志");
            sendResponse(exchange, 200, "{\"message\":\"日志已清空\"}");
        } else {
            sendResponse(exchange, 500, "{\"error\":\"无法获取监控实例\"}");
        }
    }
    
    private void handleExportAll(HttpExchange exchange) throws IOException {
        // 导出所有数据的逻辑
        PACC monitor = PACC.getInstance();
        if (monitor != null) {
            // 创建临时ZIP文件
            File tempZip = File.createTempFile("pacc-export-", ".zip");
            
            try (ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(tempZip))) {
                // 添加设备信息
                addFileToZip(zipOut, "devices.json", toJson(new ArrayList<>(monitor.deviceInfoMap.values())));
                
                // 添加进程信息
                addFileToZip(zipOut, "processes.json", toJson(new ArrayList<>(monitor.previousProcesses)));
                
                // 添加日志内容
                addFileToZip(zipOut, "logs.txt", monitor.logArea.getText());
            }
            
            // 发送ZIP文件
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"pacc-export.zip\"");
            exchange.sendResponseHeaders(200, tempZip.length());
            
            try (OutputStream os = exchange.getResponseBody();
                 FileInputStream fis = new FileInputStream(tempZip)) {
                byte[] buffer = new byte[1024];
                int len;
                while ((len = fis.read(buffer)) > 0) {
                    os.write(buffer, 0, len);
                }
            }
            
            // 删除临时文件
            tempZip.delete();
        } else {
            sendResponse(exchange, 500, "{\"error\":\"无法获取监控实例\"}");
        }
    }
    
    private void handleSystemInfo(HttpExchange exchange) throws IOException {
        PACC monitor = PACC.getInstance();
        Map<String, Object> info = new HashMap<>();
        
        info.put("osName", System.getProperty("os.name"));
        info.put("arch", System.getProperty("os.arch"));
        info.put("javaVersion", System.getProperty("java.version"));
        
        // 运行时间
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        info.put("uptime", String.format("%d小时%d分钟", 
                  TimeUnit.MILLISECONDS.toHours(uptime),
                  TimeUnit.MILLISECONDS.toMinutes(uptime) % 60));
        
        // 内存信息
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        long usedMemory = memoryBean.getHeapMemoryUsage().getUsed();
        long maxMemory = memoryBean.getHeapMemoryUsage().getMax();
        info.put("memoryUsed", formatBytes(usedMemory));
        info.put("memoryTotal", formatBytes(maxMemory));
        
        // 设备和进程数量
        info.put("deviceCount", monitor != null ? monitor.deviceInfoMap.size() : 0);
        info.put("processCount", monitor != null ? monitor.previousProcesses.size() : 0);
        
        sendResponse(exchange, 200, toJson(info));
    }
    
    private void addFileToZip(ZipOutputStream zipOut, String fileName, String content) throws IOException {
        ZipEntry zipEntry = new ZipEntry(fileName);
        zipOut.putNextEntry(zipEntry);
        zipOut.write(content.getBytes(StandardCharsets.UTF_8));
        zipOut.closeEntry();
    }
    
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp-1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
    
    // 验证令牌（与AdminCheckHandler中相同）
    private boolean validateToken(String token) {
        try {
            String decoded = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":");
            if (parts.length < 2) return false;
            
            // 检查令牌是否过期（示例：1小时有效期）
            long timestamp = Long.parseLong(parts[parts.length - 1]);
            long now = System.currentTimeMillis();
            return (now - timestamp) < 3600000; // 1小时内有效
        } catch (Exception e) {
            return false;
        }
    }
}


// 添加JSON解析辅助方法
private static Map<String, Object> parseJson(String json) {
    Map<String, Object> result = new HashMap<>();
    try {
        // 简单的JSON解析实现（生产环境建议使用专业库如Jackson或Gson）
        json = json.trim();
        if (json.startsWith("{") && json.endsWith("}")) {
            json = json.substring(1, json.length() - 1);
            String[] pairs = json.split(",");
            for (String pair : pairs) {
                String[] keyValue = pair.split(":", 2);
                if (keyValue.length == 2) {
                    String key = keyValue[0].trim().replaceAll("^\"|\"$", "");
                    String value = keyValue[1].trim().replaceAll("^\"|\"$", "");
                    result.put(key, value);
                }
            }
        }
    } catch (Exception e) {
        result.put("error", "JSON解析失败: " + e.getMessage());
    }
    return result;
}
 
    /**
     * 发送HTTP响应
     */
    private static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*"); // 允许跨域请求
        exchange.sendResponseHeaders(statusCode, response.getBytes(StandardCharsets.UTF_8).length);
        OutputStream os = exchange.getResponseBody();
        os.write(response.getBytes(StandardCharsets.UTF_8));
        os.close();
    }

    /**
     * 程序入口点
     */
    public static void main(String[] args) {
        // 记录启动时间
        LocalDateTime startTime = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        
        System.out.println("启动 USB 监控器...");
        System.out.println("启动时间: " + startTime.format(formatter));
        
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                
                // 创建监控器实例前记录时间
                LocalDateTime preInitTime = LocalDateTime.now();
                System.out.println("开始初始化 GUI: " + preInitTime.format(formatter));
                
                PACC monitor = new PACC();
                monitor.setVisible(true);
                
                // 添加窗口关闭事件，确保清理工作
                monitor.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                monitor.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        int option = JOptionPane.showConfirmDialog(
                            monitor,
                            "确定要退出程序吗？\n注意：退出后将停止所有监控功能。",
                            "确认退出",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.QUESTION_MESSAGE
                        );
                        
                        if (option == JOptionPane.YES_OPTION) {
                            // 执行清理工作
                            monitor.cleanupOnExit();
                            
                            // 退出程序
                            System.exit(0);
                        }
                    }
                });
                
                // GUI 显示后记录时间
                LocalDateTime postInitTime = LocalDateTime.now();
                System.out.println("GUI 已显示: " + postInitTime.format(formatter));
                
                // 计算初始化耗时
                long initDuration = java.time.Duration.between(preInitTime, postInitTime).toMillis();
                System.out.println("GUI 初始化耗时: " + initDuration + " 毫秒");
                
                // 将启动信息写入日志文件
                monitor.writeStartupInfoToLog(startTime, preInitTime, postInitTime);
                
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
    
    /**
     * 写入启动信息到日志文件
     */
    private void writeStartupInfoToLog(LocalDateTime startTime, LocalDateTime preInitTime, LocalDateTime postInitTime) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            
            // 确保日志目录存在
            String logDirName = config.getProperty("logDirectory", "logs");
            File logDir = new File(logDirName);
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
            
 // 写入启动信息到单独的启动日志文件
            File startupLogFile = new File(logDirName, "startup-info.log");
            try (PrintWriter startupLogWriter = new PrintWriter(new FileWriter(startupLogFile, true), true)) {
                startupLogWriter.println("===============================================");
                startupLogWriter.println("应用程序启动详细信息");
                startupLogWriter.println("启动时间: " + startTime.format(formatter));
                startupLogWriter.println("GUI 初始化开始时间: " + preInitTime.format(formatter));
                startupLogWriter.println("GUI 初始化完成时间: " + postInitTime.format(formatter));
                startupLogWriter.println("GUI 初始化耗时: " + java.time.Duration.between(preInitTime, postInitTime).toMillis() + " 毫秒");
                startupLogWriter.println("操作系统: " + System.getProperty("os.name"));
                startupLogWriter.println("Java 版本: " + System.getProperty("java.version"));
                startupLogWriter.println("Java 供应商: " + System.getProperty("java.vendor"));
                startupLogWriter.println("用户目录: " + System.getProperty("user.dir"));
                startupLogWriter.println("===============================================");
            }
            
            // 同时写入到设备日志和进程日志
            String timestamp = LocalDateTime.now().format(formatter);
            if (deviceLogWriter != null) {
                deviceLogWriter.println("[" + timestamp + "] 应用程序启动完成");
            }
            if (processLogWriter != null) {
                processLogWriter.println("[" + timestamp + "] 应用程序启动完成");
            }
            
        } catch (IOException e) {
            System.err.println("无法写入启动信息到日志文件: " + e.getMessage());
        }
    }
    
    /**
     * 关闭所有日志写入器
     */
    private void closeLogWriters() {
        try {
            if (deviceLogWriter != null) {
                deviceLogWriter.close();
            }
            if (processLogWriter != null) {
                processLogWriter.close();
            }
            if (hiddenProcessLogWriter != null) {
                hiddenProcessLogWriter.close();
            }
            if (cheatLogWriter != null) {
                cheatLogWriter.close();
            }
        } catch (Exception e) {
            System.err.println("关闭日志写入器时出错: " + e.getMessage());
        }
    }
    
    /**
     * 程序退出时的清理工作
     */
    private void cleanupOnExit() {
        // 隐藏作弊警告窗口
        hideCheatAlert();
        
        // 关闭所有日志写入器
        closeLogWriters();
        
        // 停止所有定时器
        if (timer != null) {
            timer.stop();
        }
        if (processTimer != null) {
            processTimer.stop();
        }
    }

}