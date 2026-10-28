// 端到端演示数据 seeder：走真实业务接口，不走 SQL 直插。
//
// 为什么必须走接口：库存余额只能由进货入库产生，出货会扣减并写流水，
// 应收/应付与会计凭证是业务服务里的钩子自动生成的。SQL 直插只能造出"有行、
// 无账"的数据——报表能出数但和工作台/凭证对不上，那种数据在答辩上一问就穿。
//
// ── 时间纵深为什么必须走 X-Demo-Biz-Time ─────────────────────────
// 一个容易被忽略的事实：单据的 biz_date 只是"这笔业务算哪一天"，
// 而**派生账目的时间**（库存流水 occurred_at、应收到期日 due_date、
// 成本层与毛利记录）全部来自服务端 ClockProvider.now()。
// 只倒签 biz_date 而不动业务时钟，12 个月的流水会全部堆在"今天"那一格：
// 出货列表看着有 12 个月，周转/经营/呆滞/账龄却只有一个点。
// 因此本脚本每一笔请求都带上 X-Demo-Biz-Time（服务端 erp.demo.clock-override-enabled
// 打开时才生效，默认关闭；生产环境带了也没用），让"现在几点"与单据日期同步倒退。
// 库存扣减、预占、应收、凭证、成本层仍然由各自业务钩子在事务里生成 —— 与真实操作同构。
//
// 口径（全项目一致，别改）：金额单位=分，折扣=万分比（9000 表示 90%）。
// 注意不要调 /settlements/daily|monthly —— 那是日结/月结，会锁账期，
// 锁完之后本脚本和界面都无法再写入。
//
// ── 用法 ─────────────────────────────────────────────────────────
//   node scripts/seed-demo.mjs                 # 12 个月 + 全模块（答辩级，默认）
//   node scripts/seed-demo.mjs --months=6      # 只铺最近 6 个自然月
//   node scripts/seed-demo.mjs --products=40   # 商品规模（款数）
//   node scripts/seed-demo.mjs --quick         # 12 款 × 2 个月，只验接口通不通
//   node scripts/seed-demo.mjs --force         # 跳过幂等检查，再追加一整波
//   node scripts/seed-demo.mjs --pending       # 只补中间态与异常样本
//   node scripts/seed-demo.mjs --reset         # ★ 先清空业务数据再灌（主数据/IAM/科目保留）
//   ERP_API=http://127.0.0.1:8080/api/v1 ERP_USER=admin ERP_PASS=Admin@12345 node scripts/seed-demo.mjs
//
// ── 为什么批量阶段要临时关掉审批流 ───────────────────────────────
// 每张单据走审批要多 2~3 次请求（建实例 + 逐级签 + 回读），上千张单就是几千次。
// 历史单据的审批实例没人会点开看，所以批量阶段关流、让 submit→approve 直通，
// 最后一段再重新开流去造"停在审批中"的单据 —— 待办中心看到的仍然是真实例。

import { randomUUID } from 'node:crypto'

const BASE = (process.env.ERP_API ?? 'http://127.0.0.1:8080/api/v1').replace(/\/$/, '')
const USER = process.env.ERP_USER ?? 'admin'
const PASS = process.env.ERP_PASS ?? 'Admin@12345'
const FORCE = process.argv.includes('--force')
/** 只补中间态 + 异常样本，不再灌一整波进出货。 */
const PENDING_ONLY = process.argv.includes('--pending')
/** 冒烟模式：少量商品 × 2 个月，用来验证接口形状，几分钟跑完。 */
const QUICK = process.argv.includes('--quick')
/** 只补主数据（商品/客户/供应商/组织架构/员工），不碰单据。用于给已有库补员工。 */
const MASTER_ONLY = process.argv.includes('--master-only')
/** 清空业务数据后重灌（主数据 / IAM / 科目 / 仓库 / 审批流配置保留）。 */
const RESET = process.argv.includes('--reset')

/** 读 --key=value 形式的位置无关参数。 */
function argNum(name, fallback) {
  const hit = process.argv.find((a) => a.startsWith(`--${name}=`))
  if (!hit) return fallback
  const v = Number(hit.split('=')[1])
  return Number.isFinite(v) && v > 0 ? v : fallback
}

const TODAY = new Date()
const STARTED = Date.now()

// ═══════════════════════════ 计划 ═══════════════════════════

/**
 * 12 个自然月的经营节奏：1.0 为平季，直接乘到当月单量上。
 *
 * 对齐零售实况：1 月春节前备货冲量、2 月春节停摆、6 月 618、7~8 月淡季、
 * 11 月双 11 全年峰值。这样工作台趋势、毛利、周转、账龄才有"形状"，
 * 而不是一条水平线——评委看的是曲线有没有季节性，不是总量够不够大。
 */
const SEASON = {
  1: 1.18, // 春节前备货
  2: 0.46, // 春节停摆
  3: 0.82,
  4: 0.9,
  5: 0.96,
  6: 1.32, // 618
  7: 0.68, // 淡季
  8: 0.62, // 淡季
  9: 1.02, // 秋冬上新
  10: 1.16, // 国庆 + 双11 预热
  11: 1.34, // 双11
  12: 1.08,
}

const MONTH_COUNT = QUICK ? 2 : argNum('months', 12)
const PRODUCT_LIMIT = QUICK ? 12 : argNum('products', 120)
/**
 * 每月基准出货单数，按 SEASON 缩放。
 *
 * <p>进货单数不再是独立参数：采购量由**上月真实动销**推导（低于下限补到上限），
 * 手工指定"每月进几单"只会在淡季压库存、旺季断货，两头的报表都不像真账。
 */
const SALES_BASE = argNum('sales', 40)
/** 主数据规模。 */
const CUSTOMER_LIMIT = argNum('customers', 40)
const SUPPLIER_LIMIT = argNum('suppliers', 20)
/** 布尔开关：--no-anomalies / --no-inflight */
const WITH_ANOMALIES = !process.argv.includes('--no-anomalies')
const WITH_INFLIGHT = !process.argv.includes('--no-inflight')

const PLAN = {
  warehouses: [
    { code: 'DM-WH-SH', name: '上海主仓', priority: 10, main: true },
    { code: 'DM-WH-GZ', name: '广州分仓', priority: 8, main: true },
    { code: 'DM-WH-HZ', name: '杭州分仓', priority: 6, main: true },
    { code: 'DM-WH-CD', name: '成都分仓', priority: 5, main: true },
    { code: 'DM-WH-BJ', name: '北京分仓', priority: 4, main: true },
    { code: 'DM-WH-NJ', name: '南京路门店仓', priority: 3, main: false },
    { code: 'DM-WH-HB', name: '湖滨门店仓', priority: 3, main: false },
    { code: 'DM-WH-RT', name: '退货仓', priority: 1, main: false },
  ],
  /** 供应商按品类分组：进货单的供应商要和商品对得上，否则一问就露。 */
  suppliers: [
    { name: '华阳纺织', cat: ['上装'] },
    { name: '锦绣针织厂', cat: ['上装'] },
    { name: '恒丰制衣', cat: ['上装', '下装'] },
    { name: '锦程服饰', cat: ['上装', '下装'] },
    { name: '云锦梭织', cat: ['上装', '下装', '家居'] },
    { name: '三和制衣', cat: ['下装'] },
    { name: '新元牛仔', cat: ['下装'] },
    { name: '东方织造', cat: ['上装', '下装'] },
    { name: '海派服饰', cat: ['上装', '下装'] },
    { name: '远帆鞋业', cat: ['鞋'] },
    { name: '步云鞋材', cat: ['鞋'] },
    { name: '足下鞋业', cat: ['鞋'] },
    { name: '千里行鞋业', cat: ['鞋'] },
    { name: '瑞祥辅料', cat: ['配饰'] },
    { name: '织锦配饰', cat: ['配饰'] },
    { name: '巧手工艺', cat: ['配饰'] },
    { name: '暖居家纺', cat: ['家居'] },
    { name: '素棉家居', cat: ['家居'] },
    { name: '明德包装', cat: ['包装'] },
    { name: '彩印包装厂', cat: ['包装'] },
  ],
  /**
   * 客户按渠道分组：加盟 / 百货 / 电商 / 直播 / 团购。
   * 折扣率与账期各不相同 —— 客户贡献、账龄分档、信用占用才有梯度。
   * 第四项 creditDays、第五项 creditLimit（万元）决定该客户的信用画像。
   */
  customers: [
    ['优品服饰店', 8500, 'A', 30, 60], ['城南百货', 9500, 'A', 45, 120],
    ['季风电商', 8200, 'A', 15, 40], ['西岸买手店', 9000, 'B', 30, 30],
    ['北辰团购', 8800, 'B', 10, 15], ['海雾直播工作室', 7800, 'A', 7, 25],
    ['长街档口', 9200, 'C', 0, 8], ['青柠集合店', 9000, 'B', 30, 35],
    ['木棉服饰', 8600, 'A', 45, 80], ['拾光集合店', 9000, 'B', 30, 30],
    ['一川商贸', 8700, 'B', 20, 40], ['星野服饰', 8500, 'A', 30, 70],
    ['溪山百货', 9500, 'B', 60, 100], ['明州服饰城', 9300, 'B', 45, 90],
    ['云舒电商', 8100, 'A', 15, 45], ['白鹭直播基地', 7800, 'A', 7, 30],
    ['城南优选', 8900, 'B', 20, 25], ['花间集', 9100, 'C', 0, 10],
    ['半岛服饰', 8600, 'B', 30, 40], ['长宁加盟店', 8400, 'C', 15, 20],
    ['锦鲤百货', 9400, 'B', 45, 85], ['微光买手店', 9000, 'C', 0, 12],
    ['春秋服饰', 8700, 'B', 30, 45], ['禾木商贸', 8800, 'B', 20, 35],
    ['三千集', 9200, 'C', 0, 15], ['潮汐直播', 7900, 'A', 7, 28],
    ['城南团购', 8800, 'B', 10, 18], ['蓝湾服饰', 8600, 'B', 30, 38],
    ['拾贰月', 9100, 'C', 0, 14], ['越界买手店', 9000, 'C', 15, 22],
    ['东篱百货', 9400, 'B', 45, 75], ['简白服饰', 8500, 'C', 0, 16],
    ['星桥电商', 8200, 'A', 15, 42], ['千里目直播', 7700, 'A', 7, 26],
    ['半山集合店', 9000, 'C', 0, 18], ['丝路商贸', 8800, 'B', 30, 40],
    ['早安服饰', 8600, 'C', 15, 24], ['观澜百货', 9300, 'B', 60, 110],
    ['晨曦团购', 8900, 'C', 10, 20], ['无界买手店', 9000, 'C', 0, 15],
  ],
  /**
   * 组织架构：8 个部门 + 各部门岗位。
   *
   * <p>为什么要造这一层：员工如果没有部门岗位，列表里就只有"张三 / 销售员"，
   * 看不出这是一家有分工的公司；而组织架构页（FR-V3-23）也会是空的。
   * 部门挂在迁移自带的"总部"下，岗位挂在部门下 —— 与真实企业一致。
   */
  departments: [
    { code: 'DM-D-SAL', name: '销售部', positions: [
      ['DM-P-SAL-MGR', '销售主管', 3], ['DM-P-SAL-REP', '销售代表', 1]] },
    { code: 'DM-D-PUR', name: '采购部', positions: [
      ['DM-P-PUR-MGR', '采购主管', 3], ['DM-P-PUR-BUY', '采购专员', 1]] },
    { code: 'DM-D-WHS', name: '仓储物流部', positions: [
      ['DM-P-WHS-MGR', '仓储主管', 3], ['DM-P-WHS-KEEP', '仓管员', 1], ['DM-P-WHS-DRV', '配送员', 1]] },
    { code: 'DM-D-FIN', name: '财务部', positions: [
      ['DM-P-FIN-MGR', '财务主管', 3], ['DM-P-FIN-ACC', '会计', 2], ['DM-P-FIN-CSH', '出纳', 1]] },
    { code: 'DM-D-MKT', name: '市场部', positions: [
      ['DM-P-MKT-MGR', '市场主管', 3], ['DM-P-MKT-OPS', '运营专员', 1]] },
    { code: 'DM-D-HR', name: '行政人事部', positions: [
      ['DM-P-HR-MGR', '人事主管', 3], ['DM-P-HR-ADM', '行政专员', 1]] },
    { code: 'DM-D-EC', name: '电商运营部', positions: [
      ['DM-P-EC-MGR', '电商主管', 3], ['DM-P-EC-OPS', '店铺运营', 1], ['DM-P-EC-CS', '在线客服', 1]] },
  ],
  /**
   * 员工：姓名 / 部门 / 岗位 / 角色 / 手机 / 入职日期。
   *
   * <p>角色按岗位给，而不是"人人 ADMIN"：满屏管理员的系统在答辩上会被立刻质疑
   * 权限模型是不是摆设。仓管员给 VIEWER，后面还会给他们配"只看本仓"的数据范围。
   */
  staff: [
    ['chenliqun', '陈立群', 'DM-D-HR', 'DM-P-HR-MGR', 'ADMIN', '13905170011', '2019-03-04'],
    ['zhangwei', '张伟', 'DM-D-SAL', 'DM-P-SAL-MGR', 'SALESPERSON', '13905170012', '2020-06-15'],
    ['lijing', '李静', 'DM-D-SAL', 'DM-P-SAL-REP', 'SALESPERSON', '13905170013', '2021-09-01'],
    ['wanghaitao', '王海涛', 'DM-D-SAL', 'DM-P-SAL-REP', 'SALESPERSON', '13905170014', '2022-03-21'],
    ['liumin', '刘敏', 'DM-D-SAL', 'DM-P-SAL-REP', 'SALESPERSON', '13905170015', '2022-11-07'],
    ['zhaopeng', '赵鹏', 'DM-D-SAL', 'DM-P-SAL-REP', 'SALESPERSON', '13905170016', '2023-02-13'],
    ['sunyawen', '孙雅文', 'DM-D-SAL', 'DM-P-SAL-REP', 'SALESPERSON', '13905170017', '2023-08-28'],
    ['zhouchen', '周晨', 'DM-D-SAL', 'DM-P-SAL-REP', 'SALESPERSON', '13905170018', '2024-04-08'],
    ['wugang', '吴刚', 'DM-D-SAL', 'DM-P-SAL-REP', 'SALESPERSON', '13905170019', '2024-09-02'],
    ['zhengxiaodong', '郑晓东', 'DM-D-PUR', 'DM-P-PUR-MGR', 'PURCHASER', '13905170020', '2020-05-18'],
    ['malina', '马丽娜', 'DM-D-PUR', 'DM-P-PUR-BUY', 'PURCHASER', '13905170021', '2022-07-11'],
    ['shaowenbo', '邵文博', 'DM-D-PUR', 'DM-P-PUR-BUY', 'PURCHASER', '13905170022', '2023-03-27'],
    ['huangzhiqiang', '黄志强', 'DM-D-PUR', 'DM-P-PUR-BUY', 'PURCHASER', '13905170023', '2024-01-15'],
    ['xujianguo', '徐建国', 'DM-D-WHS', 'DM-P-WHS-MGR', 'VIEWER', '13905170024', '2019-11-25'],
    ['hanxue', '韩雪', 'DM-D-WHS', 'DM-P-WHS-KEEP', 'VIEWER', '13905170025', '2021-04-19'],
    ['fanzhiyuan', '范志远', 'DM-D-WHS', 'DM-P-WHS-KEEP', 'VIEWER', '13905170026', '2022-10-10'],
    ['caowenjun', '曹文军', 'DM-D-WHS', 'DM-P-WHS-DRV', 'VIEWER', '13905170027', '2023-06-05'],
    ['heli', '何丽', 'DM-D-FIN', 'DM-P-FIN-MGR', 'FINANCE', '13905170028', '2019-08-12'],
    ['luoxiaofeng', '罗晓峰', 'DM-D-FIN', 'DM-P-FIN-ACC', 'FINANCE', '13905170029', '2021-12-06'],
    ['liangxiaoyu', '梁小雨', 'DM-D-FIN', 'DM-P-FIN-CSH', 'FINANCE', '13905170030', '2023-05-22'],
    ['songjia', '宋佳', 'DM-D-MKT', 'DM-P-MKT-MGR', 'VIEWER', '13905170031', '2021-03-08'],
    ['tangning', '唐宁', 'DM-D-MKT', 'DM-P-MKT-OPS', 'VIEWER', '13905170032', '2024-02-26'],
    ['fengjie', '冯洁', 'DM-D-HR', 'DM-P-HR-ADM', 'VIEWER', '13905170033', '2022-08-15'],
    ['dengchao', '邓超', 'DM-D-EC', 'DM-P-EC-MGR', 'SALESPERSON', '13905170034', '2021-07-19'],
    ['xuqing', '许晴', 'DM-D-EC', 'DM-P-EC-OPS', 'SALESPERSON', '13905170035', '2023-09-11'],
    ['yuanyuan', '袁媛', 'DM-D-EC', 'DM-P-EC-CS', 'VIEWER', '13905170036', '2024-06-03'],
    ['jiangtao', '蒋涛', 'DM-D-EC', 'DM-P-EC-OPS', 'SALESPERSON', '13905170037', '2024-10-14'],
    ['zengmin', '曾敏', 'DM-D-HR', 'DM-P-HR-ADM', 'VIEWER', '13905170038', '2025-01-06'],
  ],
  /** 商品目录：6 大类 × 20 款。款名写成真的，别用「商品1 / 商品2」。 */  catalog: {
    上装: [
      '纯棉圆领T恤', '宽松落肩T恤', '牛津纺长袖衬衫', '水洗牛仔衬衫', '针织开衫',
      '半高领打底衫', '纯棉卫衣', '连帽卫衣', '立领夹克', '轻薄羽绒马甲',
      '西装外套', '棒球外套', '条纹polo衫', '泡泡袖衬衫', '亚麻短袖衬衫',
      '摇粒绒外套', '三合一冲锋衣', '针织马甲', '长袖打底衫', '工装衬衫',
    ],
    下装: [
      '直筒牛仔裤', '修身牛仔裤', '阔腿休闲裤', '工装长裤', '束脚运动裤',
      '高腰半身裙', '百褶裙', '牛仔短裤', '卡其休闲裤', '灯芯绒长裤',
      '高腰西装裤', '加绒打底裤', '运动短裤', '牛仔马甲裙', 'A字半身裙',
      '棉麻九分裤', '加绒卫裤', '冰丝阔腿裤', '瑜伽裤', '背带裤',
    ],
    鞋: [
      '经典帆布鞋', '小白鞋', '厚底老爹鞋', '马丁靴', '切尔西短靴',
      '乐福鞋', '轻量跑鞋', '凉拖鞋', '毛毛拖鞋', '雪地靴',
      '尖头单鞋', '玛丽珍鞋', '一脚蹬休闲鞋', '低帮板鞋', '轻徒步鞋',
      '防水雨靴', '针织袜套鞋', '复古德比鞋', '通勤中跟鞋', '厚底乐福鞋',
    ],
    配饰: [
      '羊毛混纺围巾', '羊绒针织帽', '头层牛皮皮带', '针织手套', '真丝方巾',
      '刺绣棒球帽', '羊毛贝雷帽', '帆布腰带', '中筒棉袜', '长筒针织袜',
      '宽边发带', '保暖耳罩', '桑蚕丝领带', '金属胸针', '皮质钥匙扣',
      '牛皮卡包', '零钱包', '帆布托特包', '轻便双肩包', '斜挎小方包',
    ],
    家居: [
      '纯棉家居服套装', '珊瑚绒睡袍', '加厚浴巾', '洗脸毛巾', '亚麻抱枕套',
      '水洗棉四件套', '夏凉被', '法兰绒毯', '玄关地垫', '棉拖鞋',
      '脏衣收纳袋', '香薰蜡烛', '陶瓷马克杯', '亚麻窗帘', '棉麻餐桌布',
      '沙发盖巾', '衣物防尘罩', '折叠脏衣篮', '桌面收纳盒', '实木衣架',
    ],
    包装: [
      '牛皮纸手提袋', '天地盖礼品盒', '无纺布防尘袋', '快递气泡袋', '吊牌纸卡',
      '洗水标', '金属拉链', '树脂纽扣', '缝纫线', '涤纶织带',
      '弹力松紧带', '有纺衬布', '涤塔夫里布', '织唛领标', '封箱胶带',
      '气泡缓冲膜', '感谢卡', '缎带', '干燥剂', '包装挂钩',
    ],
  },
  /** 品类元数据：价格区间（分）、可选颜色、尺码、每款取几个。 */
  categoryMeta: {
    上装: { price: [12900, 49900], colors: ['白色', '黑色', '藏青', '浅蓝', '燕麦', '雾灰', '墨绿'], sizes: ['S', 'M', 'L', 'XL'], takeColors: 3, takeSizes: 3 },
    下装: { price: [15900, 39900], colors: ['靛蓝', '黑色', '卡其', '灰色', '米白', '酒红'], sizes: ['S', 'M', 'L', 'XL'], takeColors: 3, takeSizes: 3 },
    鞋: { price: [19900, 59900], colors: ['米白', '黑色', '驼色', '深棕'], sizes: ['38', '39', '40', '41', '42'], takeColors: 2, takeSizes: 3 },
    配饰: { price: [3900, 19900], colors: ['驼色', '黑色', '藕粉', '藏青'], sizes: ['均码'], takeColors: 3, takeSizes: 1 },
    家居: { price: [5900, 29900], colors: ['米白', '浅灰', '藕粉'], sizes: ['均码'], takeColors: 2, takeSizes: 1 },
    包装: { price: [800, 5900], colors: ['本色'], sizes: ['均码'], takeColors: 1, takeSizes: 1 },
  },
}

let token = ''
const failed = []

// ═══════════════════════════ 工具 ═══════════════════════════

const pad = (n) => String(n).padStart(2, '0')
const iso = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
const ym = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}`
const NOW_YM = ym(TODAY)

/** 往回 n 天的日期（n 为负则往后）。给"近 N 天"这类落点用。 */
function dayBack(n) {
  return new Date(TODAY.getFullYear(), TODAY.getMonth(), TODAY.getDate() - n, 12)
}

/** 往回 backMonths 个自然月的第 dom 天；超过今天的部分夹到今天。 */
function dayIn(backMonths, dom) {
  const d = new Date(TODAY.getFullYear(), TODAY.getMonth() - backMonths, 1)
  const last = new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate()
  const target = new Date(d.getFullYear(), d.getMonth(), Math.min(dom, last), 12)
  return target > TODAY ? TODAY : target
}

/** 最近 N 个自然月，索引 0 最老、末尾是当月。 */
const MONTHS = Array.from({ length: MONTH_COUNT }, (_, i) => {
  const back = MONTH_COUNT - 1 - i
  const d = new Date(TODAY.getFullYear(), TODAY.getMonth() - back, 1)
  return { back, monthNo: d.getMonth() + 1, label: ym(d), first: new Date(d.getFullYear(), d.getMonth(), 1, 12) }
})

/**
 * 取"最近第 k 个月"（k=0 是当月，1 是上月…），越界时钳到最老的那个月。
 *
 * <p>为什么要钳：各种状态谱系（报价、拣货、调拨）都按 {@code i % k} 往回取月份，
 * 月份数少于 k 时会取到 {@code MONTHS[-1]}，然后在一处莫名其妙的字段上崩溃。
 * 钳住之后，短月份区间（--months=2 这种冒烟跑法）也能完整跑完。
 */
function monthAgo(k) {
  const idx = Math.max(0, MONTHS.length - 1 - k)
  return MONTHS[idx]
}

/**
 * 库存作业的落点：必须在「铺货之后」。
 *
 * <p><b>为什么不能用 dayBack(任意天数)</b>：移动加权成本按流水时间顺序回放
 * （{@code ORDER BY occurred_at, id}）。若一张调拨/调整/冻结落在铺货入库之前，
 * 回放时就会出现"成本池数量不足，无法出库"—— pool 里还没进货，却先出了货，
 * 整个 /cost/recalculate 直接 500，毛利与库存金额两张报表全部无数据。
 *
 * <p>铺货固定在 <b>最老那个月</b> 的第 2~28 天，所以库存作业只要落在"次老月及以后"
 * 就天然在铺货之后。用 {@code min(MONTHS.length - 2, k)} 把回退幅度钳住：
 * 12 个月时最多回退 10 个月（仍比铺货晚两个月），2 个月冒烟时就是当月。
 */
function opsMonth(k) {
  return monthAgo(Math.max(0, Math.min(MONTHS.length - 2, k)))
}

/**
 * 确定性伪随机：同一次参数重复跑得到同一批数字，方便对比截图。
 *
 * <p><b>必须取高位</b>：这是线性同余发生器，低位的周期极短（x_{n+1} 的低 k 位只取决于
 * 上半部分状态的低 k 位，周期最多 2^k）。直接 {@code % bound} 取低位时，
 * "随机挑一个款、随机挑一个 SKU"会长期只在一小撮组合里循环 ——
 * 实测 603 个 SKU 只有 73 个真正卖出过，呆滞报表 93% 的行是"整年零出库"。
 * 换成 {@code >>> 16} 取高位后分布才真正铺开。
 */
let seedState = 20261025
function rnd(bound) {
  seedState = (seedState * 1103515245 + 12345) & 0x7fffffff
  return (((seedState >>> 16) % bound) + 1)
}

const yuan = (fen) => (fen / 100).toFixed(2)
const fix = (v) => Number(v ?? 0).toFixed(2)
const unwrap = (d) => (Array.isArray(d) ? d : (d?.items ?? []))

/**
 * 当日业务时刻：把"现在几点"设到某个自然日的营业时段内。
 *
 * <p>为什么不是整点：库存流水按 occurred_at 排序、成本层按 (occurred_at, id) 排序。
 * 同一秒落几百条会让"先入后出"的顺序变成 id 顺序，FEFO 与成本配对都失去时间意义。
 * 这里按单据序号在 9:00~19:00 之间均匀铺开，同一天的多笔单据自然有先后。
 */
function bizTime(day, seq = 0) {
  const d = day instanceof Date ? day : new Date(day)
  const minutes = 9 * 60 + ((seq * 17) % (10 * 60)) // 9:00 ~ 19:00，步长 17 分钟
  return `${iso(d)}T${pad(Math.floor(minutes / 60))}:${pad(minutes % 60)}:00`
}

/**
 * 批次号即"这批货哪个月到的"。
 *
 * <p>入库时 {@code receivePO} 用进货单业务月份生成 {@code B202609} 这样的批次号，
 * 因此批次号可以当"到货月份"用，而且能直接做字符串比较。
 * 出库类作业（调拨/调整/盘亏）只允许挑**严格早于作业月份**的批次：
 * 同月批次可能比作业日晚到，成本回放时仍会先出后进。
 */
const batchOfMonth = (day) => `B${ym(day)}`

/** 该批次是否确定在 day 之前已经到货（批次月份严格更早）。 */
const receivedBefore = (batchNo, day) => !!batchNo && batchNo < batchOfMonth(day)

async function call(method, path, body, at) {
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers.Authorization = `Bearer ${token}`
  // 写操作必须带 X-Request-Id：后端用它做幂等键，缺了会被拦。
  if (method !== 'GET') headers['X-Request-Id'] = randomUUID()
  // ★ 让服务端的"现在几点"与本单据的业务日期同步（仅演示开关打开时生效）。
  if (at) headers['X-Demo-Biz-Time'] = at
  const res = await fetch(`${BASE}${path}`, { method, headers, body: body ? JSON.stringify(body) : undefined })
  let payload = null
  try {
    payload = await res.json()
  } catch {
    /* 非 JSON 响应，走下面的状态码分支 */
  }
  if (!res.ok) {
    const code = payload?.code ?? `HTTP ${res.status}`
    const err = new Error(`${method} ${path} -> ${code}: ${payload?.message ?? res.statusText}`)
    err.detail = { code, status: res.status, path }
    throw err
  }
  return payload?.data
}

/**
 * 分页拉全量：主数据 / 报表都可能超过一页，只取第一页会少算。
 *
 * <p><b>终止条件只能看"本页是否空"或"够不够 total"，不能拿本页条数跟请求的
 * {@code pageSize} 比</b>：后端对 size 有自己的上限（实测一次给 100 条），
 * 请求 size=400 时第一页只回 100 条，{@code 100 < 400} 会被误判成"没有下一页"，
 * 于是 427 笔应收只处理了前 122 笔 —— 剩下的全部留在未核销状态，
 * 账龄报表里 >90 天一档凭空多出一百多万，看着像没人催款。
 */
async function fetchAll(path, pageSize = 200) {
  const out = []
  for (let page = 1; page <= 80; page++) {
    const sep = path.includes('?') ? '&' : '?'
    const data = await call('GET', `${path}${sep}page=${page}&size=${pageSize}`)
    const items = unwrap(data)
    if (items.length === 0) break
    out.push(...items)
    const total = data?.total ?? out.length
    if (out.length >= total) break
  }
  return out
}

/**
 * 建主数据；编码已存在就回库里那条。
 * 这样 seeder 可以反复执行来追加单据，而不是第二次就撞 uq_*_code 全部失败。
 */
async function findOrCreate(createPath, body, listPath) {
  try {
    return await call('POST', createPath, body)
  } catch (e) {
    if (!/409|DUPL|已存在|重复/.test(e.message)) throw e
    const rows = unwrap(await call('GET', listPath))
    const hit = rows.find((r) => r.code === body.code)
    if (!hit) throw e
    return hit
  }
}

function note(where, err) {
  // 单笔失败不中断整批：让其余单据继续，最后统一列出来
  failed.push(`${where} -> ${err.message}`)
  // SEED_DEBUG=1 时立刻打印：排障要看第一个失败，而不是等最后的汇总
  if (process.env.SEED_DEBUG === '1') console.log(`    [fail] ${where} -> ${err.message}`)
}

function elapsed() {
  const s = Math.round((Date.now() - STARTED) / 1000)
  return `${Math.floor(s / 60)}分${pad(s % 60)}秒`
}

// ═══════════════════════════ 主流程 ═══════════════════════════

async function main() {
  const login = await call('POST', '/auth/login', { username: USER, password: PASS })
  token = login.token
  console.log(`登录 ${login.username}（租户 ${login.tenantCode}），权限 ${login.permissions.length} 项`)

  // 幂等闸门：调拨单只有本脚本会造，它非空就说明整批已经跑过了。
  if (!FORCE && !PENDING_ONLY) {
    const probe = await call('GET', '/inventory/transfers?page=1&size=1').catch(() => null)
    if ((probe?.total ?? 0) > 0) {
      console.log(`已存在 ${probe.total} 张调拨单，判断已跑过完整批次。要再追加请加 --force`)
      return
    }
  }

  if (RESET) await assertCleanBaseline()

  // 批量阶段关审批流，末尾再打开（见文件头说明）
  const flowState = PENDING_ONLY ? new Map() : await disableFlows()

  const warehouses = await ensureWarehouses()
  const suppliers = await ensureSuppliers()
  const customers = await ensureCustomers()
  // 组织架构必须在员工之前：员工要挂部门岗位
  const org = await ensureOrganization()
  await ensureStaff(org)
  const products = await ensureProducts(warehouses, suppliers)

  const skuCount = products.reduce((s, p) => s + p.skus.length, 0)
  console.log(`主数据：${warehouses.length} 仓 / ${suppliers.length} 供应商 / ${customers.length} 客户 / ${products.length} 款商品 / ${skuCount} 个 SKU`)

  if (MASTER_ONLY) {
    // ★ 提前返回前必须把审批流放回去：批量阶段是"临时关闭"，
    //   忘了恢复会让系统长期停在"单据全直通、待办永远为空"的状态，
    //   而且下一次跑批只会看到"关闭 0 条" —— 静默地一直关着。
    await restoreFlows(flowState)
    await ensureFlowsEnabled()
    console.log('（--master-only：只建主数据与员工，跳过单据与对账）')
    return
  }

  if (!PENDING_ONLY) {
    await runLedger(products, customers, warehouses)
    await runQuotes(products, customers)
    await runShipmentFlow(products, customers, warehouses) // 拣货发货（与直发互斥，只用于部分订单）
    await runReturns()
    await runInventoryOps(products, warehouses)
    await ensureSafetyStock(products)
    // ★ 供应链必须在财务之前：采购退货是"冲减应付"，
    //   如果先按账期把应付付完，退货 approve 会撞
    //   PUR_RETURN_EXCEED_PAYABLE（应付单余额 0），退货单永远过不了账。
    //   真实业务顺序也是 入库 → 退货 → 付款结算。
    await runSupplyChain(products, warehouses) // 请购 → 采购单 → 到货质检 → 采购退货
    await runFinance(customers, products)
    await runCostPipeline()
  }

  // 中间态与异常样本要真审批实例，所以先把流放回去
  await restoreFlows(flowState)
  await ensureFlowsEnabled()
  if (WITH_INFLIGHT) await runInFlight(products, customers)
  if (WITH_ANOMALIES) await runAnomalies(products, customers)

  await openingBalances()
  await verify()
}

// ─────────────────────────── 清库 ───────────────────────────

/**
 * 清空业务数据由 {@code scripts/reset-demo-data.sql} 负责，**不在本脚本内做**。
 *
 * <p>刻意分家：清库是一次性 SQL（TRUNCATE 一组派生表 + 事实表），
 * 而造数必须走 HTTP 业务接口。把 TRUNCATE 塞进 seeder 会让它悄悄变成
 * "一个能删数据的脚本" —— 谁加到流水线里都是一把没有保险的刀。
 * 因此这里只做一件事：确认库是干净的，不干净就明确告诉人下一步敲什么。
 */
async function assertCleanBaseline() {
  const probes = [
    ['/sales-orders?page=1&size=1', '出货单'],
    ['/purchase-orders?page=1&size=1', '进货单'],
  ]
  const dirty = []
  for (const [path, label] of probes) {
    const d = await call('GET', path).catch(() => null)
    if ((d?.total ?? 0) > 0) dirty.push(`${label} ${d.total} 张`)
  }
  if (dirty.length === 0) return
  console.log(`\n⚠ 库里已有业务数据：${dirty.join('、')}`)
  console.log('  直接追加会让新旧时间轴互相污染（报表里两批账混在一起）。')
  console.log('  要清出干净基线再灌，请先执行：')
  console.log('    scripts\\seed-demo.ps1 -Reset        （或 bash scripts/seed-demo.sh --reset）')
  console.log('  只想在原数据上追加：加 --force 再跑一次本脚本。')
}

// ─────────────────────────── 审批流开关 ───────────────────────────

async function disableFlows() {
  const before = new Map()
  const flows = unwrap(await call('GET', '/approvals/flows'))
  for (const f of flows) {
    const usable = (f.steps ?? []).length > 0
    before.set(f.bizType, Boolean(f.enabled) && usable)
    if (!f.enabled) continue
    if (!usable) {
      console.log(`跳过审批流 ${f.code}：没有审批节点，启用会让单据卡在 SUBMITTED`)
      continue
    }
    await call('POST', `/approvals/flows/${f.id}/enabled?enabled=false`).catch((e) => note(`关闭审批流 ${f.code}`, e))
  }
  console.log(`审批流已临时关闭：${[...before].filter(([, v]) => v).map(([k]) => k).join(', ') || '无'}`)
  return before
}

async function restoreFlows(before) {
  const flows = unwrap(await call('GET', '/approvals/flows'))
  for (const f of flows) {
    if (!before.get(f.bizType) || f.enabled) continue
    await call('POST', `/approvals/flows/${f.id}/enabled?enabled=true`)
      .then(() => console.log(`审批流已恢复：${f.code}`))
      .catch((e) => note(`恢复审批流 ${f.code}`, e))
  }
}

/**
 * 收尾确认审批流真的"能用"。
 *
 * <p>踩过的坑：审批流一旦被关掉（`enabled=false`），单据 submit→approve 会直通，
 * 一张审批实例都不会留下 —— 待办中心、审批工作台、发货台全是空表格，
 * 而单据列表看起来一切正常。更隐蔽的是：`restoreFlows` 只恢复"本次跑批前就开着"的流，
 * 如果上一次跑批中途失败、把流关了却没走到恢复那一步，之后每次跑批都会
 * "关掉 0 条、恢复 0 条"，静默地一直关着。
 *
 * <p>所以这里不看历史状态，只保证一件事：每种业务类型至少有一条**带节点**的流是开着的。
 * 顺带把重复定义报出来 —— 同一 biz_type 有两条流时，走哪条取决于查询顺序，
 * 排查起来非常费神。
 */
async function ensureFlowsEnabled() {
  const flows = unwrap(await call('GET', '/approvals/flows'))
  const byType = new Map()
  for (const f of flows) {
    if (!byType.has(f.bizType)) byType.set(f.bizType, [])
    byType.get(f.bizType).push(f)
  }
  const fixed = []
  for (const [bizType, list] of byType) {
    if (list.length > 1) {
      console.log(`  ! ${bizType} 有 ${list.length} 条审批流定义，走哪条不确定，建议只留一条`)
    }
    const usable = list.find((f) => (f.steps ?? []).length > 0)
    if (!usable) {
      console.log(`  ! ${bizType} 没有带节点的审批流，单据会直通（待办中心不会有内容）`)
      continue
    }
    if (!usable.enabled) {
      await call('POST', `/approvals/flows/${usable.id}/enabled?enabled=true`)
        .then(() => fixed.push(usable.code))
        .catch((e) => note(`启用审批流 ${usable.code}`, e))
    }
  }
  if (fixed.length > 0) console.log(`审批流已启用：${fixed.join('、')}`)
}

// ─────────────────────────── 主数据 ───────────────────────────

async function ensureWarehouses() {
  const out = []
  for (const w of PLAN.warehouses) {
    const row = await findOrCreate(
      '/warehouses',
      { code: w.code, name: w.name, priority: w.priority, enabled: true },
      '/warehouses?page=1&size=200',
    )
    out.push({ ...row, main: w.main })
  }
  return out
}

async function ensureSuppliers() {
  const out = []
  for (const [i, s] of PLAN.suppliers.slice(0, SUPPLIER_LIMIT).entries()) {
    const row = await findOrCreate(
      '/suppliers',
      { code: `DM-SUP-${pad(i + 1)}`, name: s.name, contactName: `${s.name.slice(0, 1)}经理`, enabled: true },
      '/suppliers?page=1&size=200',
    )
    out.push({ ...row, cat: s.cat })
  }
  return out
}

/**
 * 客户：带信用额度与账期。
 *
 * <p>额度不是随便填的 —— 它决定了"哪些客户会被信用控制挡住"。
 * 这里刻意让团购/直播这类小客户额度偏紧（几万），百货/加盟偏宽（几十万），
 * 于是信用占用率报表有梯度，超期应收与超额拒单也才像真的。
 */
async function ensureCustomers() {
  const out = []
  for (const [i, [name, discountRate, level, creditDays, limitWan]] of PLAN.customers.slice(0, CUSTOMER_LIMIT).entries()) {
    const row = await findOrCreate(
      '/customers',
      { code: `DM-CUS-${pad(i + 1)}`, name, discountRate, level },
      '/customers?page=1&size=200',
    )
    // 信用额度=万元转分；账期=天。两个字段走独立接口（PUT /customers/{id}/credit）。
    const creditLimit = limitWan * 10000 * 100
    await call('PUT', `/customers/${row.id}/credit`, { creditLimit, creditDays })
      .catch((e) => note(`客户信用 ${name}`, e))
    out.push({ ...row, discountRate, creditDays, creditLimit })
  }
  return out
}

/**
 * 组织架构：部门树 + 岗位（FR-V3-23）。
 *
 * <p>员工必须挂在部门岗位上，否则"花名册"只是账号清单。
 * 幂等按 {@code code} 判断：重复跑不会建出两套同名部门。
 * 部门统一挂在迁移自带的"总部"（ROOT）下 —— 没有 ROOT 时建成顶层。
 */
async function ensureOrganization() {
  const byCode = new Map()
  const walk = (list) => {
    for (const d of list ?? []) {
      byCode.set(d.code, d)
      walk(d.children)
    }
  }
  walk(unwrap(await call('GET', '/org/departments?includeDisabled=true')))
  const root = byCode.get('ROOT')

  let deptCreated = 0
  for (const [i, dept] of PLAN.departments.entries()) {
    if (byCode.has(dept.code)) continue
    const row = await call('POST', '/org/departments', {
      code: dept.code,
      name: dept.name,
      parentId: root?.id ?? null,
      sortNo: i + 1,
    }).catch((e) => {
      note(`部门 ${dept.name}`, e)
      return null
    })
    if (row) {
      byCode.set(dept.code, row)
      deptCreated++
    }
  }

  const posByCode = new Map(unwrap(await call('GET', '/org/positions')).map((p) => [p.code, p]))
  let posCreated = 0
  for (const dept of PLAN.departments) {
    const deptId = byCode.get(dept.code)?.id
    if (!deptId) continue
    for (const [code, name, level] of dept.positions) {
      if (posByCode.has(code)) continue
      const row = await call('POST', '/org/positions', {
        departmentId: deptId, code, name, level,
      }).catch((e) => {
        note(`岗位 ${name}`, e)
        return null
      })
      if (row) {
        posByCode.set(code, row)
        posCreated++
      }
    }
  }
  console.log(
    `组织架构：${PLAN.departments.length} 个部门（新建 ${deptCreated}）/`
      + ` ${posByCode.size} 个岗位（新建 ${posCreated}）`,
  )
  return { byCode, posByCode }
}

/**
 * 员工账号：账号 + 人事档案 + 角色，一次建完。
 *
 * <p>密码统一 {@code Demo@2026erp}：满足复杂度策略（大写/小写/数字/符号），
 * 且新账号一律带"首次登录强制改密" —— 这是后端建号的固有行为，
 * 演示时登录后会被要求改密，正好能讲这条策略。
 *
 * <p>不追求"每人一个真实密码"：演示环境的账号是**组织样本**，
 * 不是要交给真人使用的凭据。
 */
const STAFF_PASSWORD = 'Demo@2026erp'

async function ensureStaff(org) {
  const existing = new Map(
    unwrap(await call('GET', '/iam/employees?page=1&size=200')).map((e) => [e.username, e]),
  )
  let created = 0
  let repaired = 0
  for (const [i, [username, name, deptCode, posCode, role, phone, hire]] of PLAN.staff.entries()) {
    const deptId = org.byCode.get(deptCode)?.id ?? null
    const posId = org.posByCode.get(posCode)?.id ?? null
    const profile = {
      employeeNo: `EMP-${String(i + 1).padStart(4, '0')}`,
      departmentId: deptId,
      positionId: posId,
      phone,
      email: `${username}@youpin.example.com`,
      hireDate: hire,
    }
    const hit = existing.get(username)
    if (hit) {
      // 已存在则补档案：上一次跑批如果部门还没建好，这批人的 department_id 会是空，
      // 光靠"跳过"永远补不上。覆盖式写入是安全的 —— 这是演示数据，以 PLAN 为准。
      if (Number(hit.departmentId ?? 0) !== Number(deptId ?? 0)) {
        await call('PUT', `/iam/employees/${hit.id}`, profile)
          .then(() => {
            repaired++
          }, (e) => note(`补齐员工档案 ${name}`, e))
      }
      continue
    }
    await call('POST', '/iam/users', {
      username,
      password: STAFF_PASSWORD,
      displayName: name,
      enabled: true,
      roles: [role],
      ...profile,
    }).then(
      () => {
        created++
      },
      (e) => note(`员工 ${name}`, e),
    )
  }
  console.log(
    `员工：${PLAN.staff.length} 人（本次新建 ${created}、补档案 ${repaired}，初始密码 ${STAFF_PASSWORD}）`,
  )
}

/**
 * 商品 + SKU 矩阵。
 *
 * SKU 用 /skus/matrix 一次建 颜色 × 尺码 一整片：600 个 SKU 逐条 POST 要好几分钟，
 * 矩阵接口只花几十次。已存在就跳过（重跑不撞唯一约束，也不会重复建）。
 */
async function ensureProducts(warehouses, suppliers) {
  const flat = Object.entries(PLAN.catalog)
    .flatMap(([cat, names]) => names.map((name) => ({ cat, name })))
    .slice(0, PRODUCT_LIMIT)

  const mainWarehouses = warehouses.filter((w) => w.main)
  const out = []
  let created = 0

  for (const [i, item] of flat.entries()) {
    const meta = PLAN.categoryMeta[item.cat]
    const [lo, hi] = meta.price
    const retail = Math.round((lo + ((i * 1700) % (hi - lo))) / 100) * 100
    const cost = Math.round(retail * (0.42 + ((i * 7) % 13) / 100))

    const product = await findOrCreate(
      '/products',
      { code: `DM-P-${pad(i + 1)}`, name: item.name, category: item.cat, season: '全季' },
      '/products?page=1&size=400',
    )

    let skus = await fetchAll(`/skus?productId=${product.id}`, 200)
    if (skus.length === 0) {
      await call('POST', '/skus/matrix', {
        productId: product.id,
        colors: meta.colors.slice(0, meta.takeColors),
        sizes: meta.sizes.slice(0, meta.takeSizes),
        retailPriceAmount: retail,
        enabled: true,
      }).catch((e) => note(`SKU 矩阵 ${item.name}`, e))
      skus = await fetchAll(`/skus?productId=${product.id}`, 200)
      created += skus.length
    }

    const supplierPool = suppliers.filter((s) => s.cat.includes(item.cat))
    out.push({
      product,
      cat: item.cat,
      name: item.name,
      retail,
      cost,
      // 每款固定一个主仓：销售单只有一个仓库字段，跨仓卖会让库存校验对不上
      warehouse: mainWarehouses[i % mainWarehouses.length],
      skus,
      supplier: supplierPool.length ? supplierPool[i % supplierPool.length] : suppliers[i % suppliers.length],
    })
  }
  if (created > 0) console.log(`新建 SKU ${created} 个`)
  return out
}

// ─────────────────────────── 库存台账 ───────────────────────────

/**
 * 本地可用量台账。
 *
 * 后端在提交/发货时会校验可用量，靠"猜剩下的够不够"会让整批单大量被拦。
 * 这里每次入库 +qty、出库 −qty，用 `${skuId}:${warehouseId}` 做键；
 * 只用于选货，不用于对账 —— 对账以 verify() 的接口读数为准。
 */
const avail = new Map()
const availKey = (skuId, whId) => `${skuId}:${whId}`
const availOf = (skuId, whId) => avail.get(availKey(skuId, whId)) ?? 0

function availAdd(skuId, whId, delta) {
  const k = availKey(skuId, whId)
  avail.set(k, Math.max(0, (avail.get(k) ?? 0) + delta))
}

async function primeAvail() {
  avail.clear()
  for (const b of await fetchAll('/inventory', 200)) {
    avail.set(availKey(Number(b.skuId), Number(b.warehouseId)), Number(b.availableQty ?? 0))
  }
}

// ─────────────────────────── 单据 ───────────────────────────

async function createPO(supplierId, warehouseId, lines, bizDate, remark, at) {
  let order = await call('POST', '/purchase-orders', {
    supplierId, warehouseId, bizDate, remark,
    lines: lines.map((l) => ({ skuId: l.skuId, qty: l.qty, unitPriceAmount: l.unitPriceAmount })),
  }, at)
  order = await call('POST', `/purchase-orders/${order.id}/submit`, undefined, at)
  return call('POST', `/purchase-orders/${order.id}/approve`, undefined, at)
}

/**
 * 收货入库。
 *
 * <p>qcQty 是刻意留的：偶尔让 1~2 件不合格（破损/色差），
 * 于是到货质检页有真实的 PARTIAL 记录，应付金额也严格小于到货数量 —— 
 * "只有合格品入账"这条规则在数据上是自洽的，评委追问时点得开。
 */
async function receivePO(order, warehouseId, at, opts = {}) {
  const received = await call('POST', `/purchase-orders/${order.id}/receive`, {
    lines: order.lines.map((l) => {
      const qc = opts.qcRatio ? Math.max(0, Math.round(l.qty * (1 - opts.qcRatio))) : l.qty
      return {
        lineId: l.id,
        qty: l.qty,
        qcQty: qc,
        qcNote: qc < l.qty ? `质检不合格 ${l.qty - qc} 件` : null,
        batchNo: opts.batchNo ?? `B${String(order.bizDate ?? '').replace(/-/g, '').slice(0, 6)}`,
      }
    }),
  }, at)
  for (const l of received.lines ?? order.lines) {
    availAdd(Number(l.skuId), Number(warehouseId), Number(l.qcQty ?? l.qty))
  }
  return received
}

async function createSO(customer, warehouseId, lines, bizDate, remark, at) {
  let order = await call('POST', '/sales-orders', {
    customerId: customer.id, warehouseId, bizDate, remark,
    lines: lines.map((l) => ({
      skuId: l.skuId,
      qty: l.qty,
      unitPriceAmount: l.unitPriceAmount,
      discountRate: l.discountRate ?? customer.discountRate ?? 9000,
    })),
  }, at)
  order = await call('POST', `/sales-orders/${order.id}/submit`, undefined, at)
  return call('POST', `/sales-orders/${order.id}/approve`, undefined, at)
}

/** 已发货单记账：退货要用原单行 id，所以在这里留下凭据。 */
const shippedOrders = []

async function shipSO(order, warehouseId, remark, at) {
  const shipped = await call('POST', `/sales-orders/${order.id}/ship`, undefined, at)
  const lines = shipped.lines ?? order.lines
  for (const l of lines) availAdd(Number(l.skuId), Number(warehouseId), -Number(l.qty))
  shippedOrders.push({
    id: Number(order.id),
    orderNo: order.orderNo,
    customerId: Number(order.customerId),
    bizDate: order.bizDate,
    lines: lines.map((l) => ({
      lineId: Number(l.id),
      skuId: Number(l.skuId),
      qty: Number(l.qty),
    })),
    remark,
  })
  return shipped
}

/**
 * 从某个仓里挑 n 个够货的 SKU。
 * 挑不到就返回空数组，调用方跳过这张单 —— 硬凑会让整批单被库存校验拦掉。
 */
function pickStocked(pool, warehouseId, count, minQty = 3) {
  const picked = []
  if (pool.length === 0) return picked
  for (let guard = 0; guard < 120 && picked.length < count; guard++) {
    const p = pool[rnd(pool.length) - 1]
    if (!p?.skus?.length) continue
    const sku = p.skus[rnd(p.skus.length) - 1]
    const have = availOf(Number(sku.id), Number(warehouseId))
    if (have < minQty) continue
    if (picked.some((x) => Number(x.sku.id) === Number(sku.id))) continue
    picked.push({ product: p, sku, have })
  }
  return picked
}

// ─────────────────────────── 12 个月经营 ───────────────────────────

/**
 * 经营流水。
 *
 * 顺序：首月大批量铺货（换季上新）→ 逐月补货 → 逐月出货。
 * 铺货让每个 SKU 都有初始仓位，之后每月只补热销款；冷门款自然沉淀成呆滞，
 * 这正好是「库存周转 / 呆滞分析」两张报表要的东西。
 */
async function runLedger(products, customers, warehouses) {
  await primeAvail()
  console.log(`\n── 经营流水（${MONTHS.length} 个月）── 起始可用库存 ${[...avail.values()].reduce((s, v) => s + v, 0)} 件`)

  const mainWh = warehouses.filter((w) => w.main)
  const hotPool = products.slice(0, Math.max(6, Math.round(products.length * 0.2)))
  const longTail = products.slice(hotPool.length)

  let poCount = 0
  let soCount = 0
  let unitIn = 0
  let unitOut = 0
  let seq = 0

  // ── 期初铺货：按仓分组，每 8 行一张进货单 ──
  //
  // ★ 日期必须落在**首个销售月之前**（这里用 MONTHS.length 往回，即窗口前一个月）。
  //   早先用 dayIn(MONTHS.length - 1, 2~28) 把铺货撒在首月 2~28 号，
  //   而首月的出货单是 1~27 号 —— 同一 SKU 的出货会排在自己的入库之前。
  //   后端校验看的是余额表（此时铺货已经全部入库），所以单据能建成；
  //   但成本引擎按 occurred_at 回放时那一池还没进过货，直接抛
  //   "成本池数量不足，无法出库"，/cost/recalculate 整批 500，毛利报表全空。
  const byWh = new Map()
  for (const p of products) {
    const k = Number(p.warehouse.id)
    if (!byWh.has(k)) byWh.set(k, [])
    byWh.get(k).push(p)
  }
  for (const [whId, list] of byWh) {
    const entries = list.flatMap((p) => p.skus.map((s) => ({ p, s })))
    for (let i = 0; i < entries.length; i += 8) {
      const chunk = entries.slice(i, i + 8)
      const supplier = chunk[0].p.supplier
      // 铺货量按"一个半月的动销"给：给太多会让期末库存与应付虚高，
      // 周转率被压到 0.5 次/年这种不像人做生意的水位。
      const lines = chunk.map(({ p, s }) => ({
        skuId: Number(s.id),
        qty: 12 + rnd(12),
        unitPriceAmount: p.cost,
      }))
      const day = dayIn(MONTHS.length, 2 + rnd(24))
      const at = bizTime(day, seq++)
      try {
        const order = await createPO(supplier.id, whId, lines, iso(day), '换季上新铺货', at)
        await receivePO(order, whId, at)
        poCount++
        unitIn += lines.reduce((s, l) => s + l.qty, 0)
      } catch (e) {
        note(`铺货 ${chunk[0].p.name} @${iso(day)}`, e)
      }
    }
  }
  console.log(`  期初铺货：${poCount} 张进货单，入库 ${unitIn} 件（${elapsed()}）`)

  // ── 逐月：补货 + 出货 ──
  //
  // 补货水位跟着**上个月的真实动销**走，而不是给一个固定数字。
  // 固定水位（原来 floor=12 / target≈26）在 600 个 SKU 上是灾难：
  // 每个 SKU 常年压着 26 件，合计 1.5 万件，而全年销量也只有 1 万件级别，
  // 于是库存周转不到 1 次/年、应付账款比全年采购还高 —— 一看就不是做生意的账。
  let soldPrev = new Map() // 上月每个 SKU×仓 的实际出货量
  let soldCur = new Map()
  for (const [mi, month] of MONTHS.entries()) {
    const weight = SEASON[month.monthNo] ?? 1
    // 月份内的增量要单独记：直接用累计值打日志会让人以为"每月都在涨"，
    // 而实际单量可能因为库存不足被跳单压低了 —— 这两个数必须分开看。
    const poStart = poCount
    const soStart = soCount
    const skipStart = failed.length

    // 补货：按"上月动销 × 安全系数"定水位，低于下限的补到上限。
    //
    // ★ 日期必须落在月初 1~5 号：同月出货在 6~28 号，
    //   这样"本月补的货"一定排在本月出货之前，成本回放不会先出后进。
    const topUp = new Map() // `${supplierId}:${warehouseId}` -> { supplier, wh, lines }
    for (const wh of mainWh) {
      for (const p of products) {
        if (Number(p.warehouse.id) !== Number(wh.id)) continue
        for (const s of p.skus) {
          const key = `${s.id}:${wh.id}`
          const have = availOf(Number(s.id), Number(wh.id))
          const prev = soldPrev.get(key) ?? 0
          // 下限 = 半个月的量，上限 = 两个月；没卖过的款只留一点样品库存
          const floor = Math.max(3, Math.round(prev * 0.6))
          const target = Math.max(6, Math.round(prev * 1.8))
          if (have >= floor) continue
          const supKey = `${p.supplier.id}:${wh.id}`
          if (!topUp.has(supKey)) topUp.set(supKey, { supplier: p.supplier, wh, lines: [] })
          topUp.get(supKey).lines.push({
            skuId: Number(s.id),
            qty: Math.max(4, target - have),
            unitPriceAmount: p.cost,
          })
        }
      }
    }
    let poIdx = 0
    // 一张采购单最多 30 行。按缺口补货时会有几百个 SKU 同时触发，
    // 用 8 行/单会拆成每月五六十张单 —— 一个月给 20 个供应商开 60 张采购单，
    // 采购员看了会摇头，而且采购单列表全是同一天的碎单。
    const PO_CHUNK = 30
    for (const { supplier, wh, lines } of topUp.values()) {
      for (let i = 0; i < lines.length; i += PO_CHUNK) {
        const chunk = lines.slice(i, i + PO_CHUNK)
        const day = dayIn(month.back, 1 + rnd(4))
        const at = bizTime(day, seq++)
        try {
          const order = await createPO(supplier.id, Number(wh.id), chunk, iso(day),
            `月初补货 ${wh.name}`, at)
          // 每 6 张补货留一次质检不合格，让到货质检页有真实的 PARTIAL 行
          await receivePO(order, Number(wh.id), at, poIdx++ % 6 === 0 ? { qcRatio: 0.08 } : {})
          poCount++
          unitIn += chunk.reduce((s, l) => s + l.qty, 0)
        } catch (e) {
          note(`补货 ${wh.name} @${iso(day)}`, e)
        }
      }
    }

    // 出货：七成走热销款，三成走长尾（长尾也有动销记录，呆滞才不是全零）
    //
    // ★ 两个细节决定"像不像在经营"：
    //   ① 一张单 5~8 个款，而不是 1~2 个 —— 少款单会让 SKU 动销面太窄，
    //      600 个 SKU 里一半整年不动，呆滞报表直接从 20% 涨到 90%；
    //   ② 主仓没货就换下一个仓发货 —— 真实业务员不会因为默认仓缺货就不开单。
    //      原写法固定取一个仓，缺货直接 continue，把"缺货"记账成了"没生意"。
    const soTarget = Math.round(SALES_BASE * weight)
    const channelOf = (name) => (name.includes('直播') ? '直播备货'
      : name.includes('电商') ? '电商入仓'
        : name.includes('团购') ? '团购集采'
          : name.includes('百货') ? '百货补货'
            : name.includes('买手') ? '买手店选品'
              : name.includes('加盟') ? '加盟店补货'
                : '门店补货')
    for (let n = 0; n < soTarget; n++) {
      const customer = customers[(mi * 5 + n * 3) % customers.length]
      const pool = n % 10 < 7 ? hotPool : longTail
      let picked = []
      let warehouse = null
      for (let hop = 0; hop < mainWh.length && picked.length === 0; hop++) {
        const w = mainWh[(n + mi + hop) % mainWh.length]
        picked = pickStocked(pool, w.id, 8 + (rnd(7) - 1), 4)
        if (picked.length > 0) warehouse = w
      }
      if (picked.length === 0) continue
      const lines = picked.map(({ product, sku, have }) => ({
        skuId: Number(sku.id),
        qty: Math.min(4 + rnd(10), have),
        unitPriceAmount: product.retail,
        discountRate: customer.discountRate,
      }))
      if (lines.length === 0) continue
      const remark = channelOf(customer.name)
      const day = dayIn(month.back, 6 + rnd(22))
      const at = bizTime(day, seq++)
      try {
        const order = await createSO(customer, warehouse.id, lines, iso(day), remark, at)
        await shipSO(order, warehouse.id, remark, at)
        soCount++
        unitOut += lines.reduce((s, l) => s + l.qty, 0)
        // 记下每个 SKU×仓 的实际动销，下个月的补货水位由它决定
        for (const l of lines) {
          const key = `${l.skuId}:${warehouse.id}`
          soldCur.set(key, (soldCur.get(key) ?? 0) + l.qty)
        }
      } catch (e) {
        note(`出货 ${customer.name} @${iso(day)}`, e)
      }
    }

    // 月末结转动销台账：本月的数据成为下月补货的依据
    soldPrev = soldCur
    soldCur = new Map()

    // 月末回款：账期短的客户当月结清。
    //
    // ★ 为什么必须按月回款：整个批次的收付款核销在最后统一做，
    //   中途应收敞口只增不减 —— 到了下半年，账期 0~15 天的客户早就"欠"满了额度，
    //   新单直接被 SAL_CREDIT_LIMIT_EXCEEDED 拦下。表现是后半年出货莫名变少，
    //   看起来像"经营变差"，实际是没回款的账把额度占死了。
    //   真实业务里这类客户本来就没账期，月结即清。
    await settleShortTerm(customers, month)

    console.log(
      `  ${month.label}：进货 ${String(poCount - poStart).padStart(3)} 单` +
        ` / 出货 ${String(soCount - soStart).padStart(3)} 单` +
        `（目标 ${Math.round(SALES_BASE * weight)} 单，本段失败 ${failed.length - skipStart} 笔；` +
        `累计入库 ${unitIn} 件、出库 ${unitOut} 件，${elapsed()}）`,
    )
  }
  console.log(`经营流水完成：进货 ${poCount} 张、出货 ${soCount} 张，在库约 ${unitIn - unitOut} 件`)
}

/**
 * 账期短（≤15 天）的客户月结回款：把到期应收结清，释放信用额度。
 *
 * <p>只动这批客户：账期 30/45/60 天的客户要留着未结应收，
 * 账龄报表的四档才有真实分布。这里按客户汇总成一笔收款单（月结单），
 * 而不是一票一单 —— 真实财务也是这么做的。
 */
async function settleShortTerm(customers, month) {
  const shortTerm = new Set(
    customers.filter((c) => Number(c.creditDays ?? 0) <= 15).map((c) => Number(c.id)),
  )
  if (shortTerm.size === 0) return
  const [y, m] = month.label.split('-').map(Number)
  const monthEnd = new Date(y, m, 0, 12)
  const day = monthEnd > TODAY ? TODAY : monthEnd

  const recv = (await fetchAll('/receivables?status=OPEN', 200))
    .filter((r) => Number(r.balance) > 0 && shortTerm.has(Number(r.customerId)))
  const byCustomer = new Map()
  for (const r of recv) {
    // 只结"本月末之前已到期"的：没到期就收钱不合业务常理
    if (r.dueDate && new Date(`${r.dueDate}T12:00:00`) > day) continue
    const k = Number(r.customerId)
    if (!byCustomer.has(k)) byCustomer.set(k, [])
    byCustomer.get(k).push(r)
  }
  let n = 0
  for (const [customerId, rows] of byCustomer) {
    const allocations = rows.map((r) => ({
      receivableId: Number(r.id),
      amount: Math.round(Number(r.balance)),
    }))
    const amount = allocations.reduce((s, a) => s + a.amount, 0)
    if (amount <= 0) continue
    await call('POST', '/customer-receipts', {
      customerId,
      amount,
      bizDate: iso(day),
      remark: `${month.label} 月结回款`,
      allocations,
    }, bizTime(day, 500 + n++)).catch((e) => note(`月结回款 客户 ${customerId}`, e))
  }
}

// ─────────────────────────── 报价 → 转单 ───────────────────────────

/**
 * 报价单：DRAFT / SUBMITTED / APPROVED / COMPLETED 四种状态都要有。
 *
 * <p>报价是销售的前置环节，页面上如果一张都没有，销售闭环看起来"从订单开始"，
 * 而这套系统的卖点恰恰是 报价 → 订单 → 拣货 → 发货 → 应收 的完整链路。
 *
 * <p>状态机是**严格线性**的：DRAFT → SUBMITTED → APPROVED → COMPLETED，
 * 不能从 DRAFT 直接 approve（会撞 SAL_QUOTE_STATUS_ILLEGAL）。
 * 所以这里按 {@code i % 4} 决定"停在哪一步"，而不是分别 if 几个动作。
 *
 * <p>转单只挑**现货足够的**报价：报价本身不预占库存，
 * 转单时才走完整建单链路（信用校验 + 预占），货不够会 INV_INSUFFICIENT_STOCK。
 */
async function runQuotes(products, customers) {
  const mainWh = products[0].warehouse
  let created = 0
  let converted = 0
  for (let i = 0; i < 12; i++) {
    const month = monthAgo(i % 3)
    const customer = customers[(i * 4) % customers.length]
    // 挑有现货的款：报价虽然不预占，但转单要真扣库存
    const p = products.find((x) => availOf(Number(x.skus[0].id), Number(x.warehouse.id)) >= 12) ?? products[0]
    const sku = p.skus[i % p.skus.length]
    const day = dayIn(month.back, 3 + rnd(20))
    const at = bizTime(day, 400 + i)
    // 0=DRAFT 1=SUBMITTED 2=APPROVED 3=转单(COMPLETED)
    const stage = i % 4
    try {
      let q = await call('POST', '/sales-quotes', {
        customerId: customer.id,
        bizDate: iso(day),
        validUntil: iso(new Date(day.getFullYear(), day.getMonth(), day.getDate() + 30)),
        remark: `季度订货报价 ${p.name}`,
        lines: [{ skuId: Number(sku.id), qty: 6 + rnd(12), unitPriceAmount: p.retail, discountRate: customer.discountRate }],
      }, at)
      if (stage >= 1) q = await call('POST', `/sales-quotes/${q.id}/submit`, undefined, at)
      if (stage >= 2) q = await call('POST', `/sales-quotes/${q.id}/approve`, undefined, at)
      if (stage === 3 && q.status === 'APPROVED') {
        await call('POST', `/sales-quotes/${q.id}/convert`, { warehouseId: Number(p.warehouse.id), remark: '客户确认，转出货单' }, at)
        converted++
      }
      created++
    } catch (e) {
      note(`报价单 客户 ${customer.name}`, e)
    }
  }
  console.log(`销售报价：${created} 张（其中 ${converted} 张已转出货单）`)
}

// ─────────────────────────── 拣货发货 ───────────────────────────

/**
 * 拣货发货全链路：发货计划 → 扫码 → 确认。
 *
 * <p>与直发（/sales-orders/{id}/ship）互斥：同一批预占只能走一条路，
 * 两边都走会重复扣库存、重复记应收、重复出凭证（后端注释已明说不在本期去重）。
 * 因此这里只对**本函数自己新建**的订单走拣货链路，历史上那些直发的单不再碰。
 *
 * <p>状态谱系也在这里铺：留几张停在 PICKING（扫了一半）、几张 PICKED 未确认，
 * 发货台和待办中心才有"待拣货 / 待确认"可点。
 */
async function runShipmentFlow(products, customers, warehouses) {
  await primeAvail()
  const mainWh = warehouses.filter((w) => w.main)
  let planned = 0
  let confirmed = 0
  let partial = 0

  for (let i = 0; i < 14; i++) {
    const month = monthAgo(i % 4)
    const customer = customers[(i * 6 + 1) % customers.length]
    const warehouse = mainWh[i % mainWh.length]
    const p = products[(i * 13) % products.length]
    const sku = p.skus[i % p.skus.length]
    const have = availOf(Number(sku.id), Number(warehouse.id))
    if (have < 8) continue
    const day = dayIn(month.back, 2 + rnd(25))
    const at = bizTime(day, 600 + i)
    try {
      const order = await createSO(customer, warehouse.id, [{
        skuId: Number(sku.id),
        qty: Math.min(5 + rnd(12), have),
        unitPriceAmount: p.retail,
        discountRate: customer.discountRate,
      }], iso(day), '拣货发货演练', at)
      const plan = await call('POST', `/sales-orders/${order.id}/shipments`, undefined, at)
      planned++

      // 计划里可能因跨仓拆成多张发货单，逐张处理
      const shipments = plan.shipments ?? []
      for (const [si, ship] of shipments.entries()) {
        const detail = await call('GET', `/shipments/${ship.id}`)
        const lines = detail.lines ?? []
        if (i % 7 === 3) {
          // 停在 PICKING：只扫第一行的一半，代表拣货员扫到一半去接电话了
          const first = lines[0]
          if (first) {
            await call('POST', `/shipments/${ship.id}/scan`, {
              shipmentLineId: first.shipmentLineId, qty: Math.max(1, Math.floor(first.plannedQty / 2)),
            }, at)
          }
          partial++
          continue
        }
        // 扫满全部行 → PICKED
        for (const line of lines) {
          await call('POST', `/shipments/${ship.id}/scan`, {
            shipmentLineId: line.shipmentLineId, qty: line.plannedQty,
          }, at)
        }
        if (i % 9 === 5) {
          // 停在 PICKED 未确认：发货台"待确认"的真实来源
          partial++
          continue
        }
        await call('POST', `/shipments/${ship.id}/confirm`, undefined, at)
        for (const line of lines) availAdd(Number(line.skuId), Number(warehouse.id), -Number(line.plannedQty))
        confirmed++
      }
    } catch (e) {
      note(`拣货发货 ${customer.name}`, e)
    }
  }
  console.log(`拣货发货：计划 ${planned} 张，确认 ${confirmed} 张，停在拣货/待确认 ${partial} 张`)
}

// ─────────────────────────── 退货 ───────────────────────────

/**
 * 退货：挂真实的原出货单与原行 id。
 * 退货入库会让原应收减少（returned_amount），账龄报表因此出现「部分退回」的行。
 */
async function runReturns() {
  const candidates = shippedOrders.filter((o) => o.lines.length > 0)
  if (candidates.length === 0) return console.log('退货单：没有可退的原单，跳过')
  const reasons = ['尺码不合', '色差明显', '质量瑕疵', '客户取消', '物流破损']
  let ok = 0
  let refunded = 0
  for (let i = 0; i < Math.min(12, candidates.length); i++) {
    const origin = candidates[(i * 37) % candidates.length]
    const line = origin.lines[0]
    const qty = Math.max(1, Math.floor(line.qty / 2))
    // ★ 退货日期必须**晚于原出货单**：退货要回补该单的库存与应收，
    //   落在出货之前会让成本回放先看到一笔"凭空入库"，配对随之错位。
    //   取原单 bizDate + 8~25 天，且不超过今天。
    const originDay = new Date(`${origin.bizDate}T12:00:00`)
    let day = new Date(originDay.getFullYear(), originDay.getMonth(),
      originDay.getDate() + 8 + (i % 18), 12)
    if (day > TODAY) day = new Date(TODAY.getFullYear(), TODAY.getMonth(), TODAY.getDate(), 12)
    const at = bizTime(day, 800 + i)
    try {
      let ret = await call('POST', '/return-orders', {
        originSalesOrderId: origin.id,
        bizDate: iso(day),
        remark: `客户退货（${reasons[i % reasons.length]}）`,
        lines: [{ originLineId: line.lineId, qty }],
      }, at)
      ret = await call('POST', `/return-orders/${ret.id}/submit`, undefined, at)
      ret = await call('POST', `/return-orders/${ret.id}/approve`, undefined, at)
      await call('POST', `/return-orders/${ret.id}/receive`, undefined, at)
      ok++
      // 每三张退货里有一张走退款核销：把对应应收的余额用"负向收款"抵掉，
      // 退款在账上表现为该应收已退回，账龄里不再占用额度。
      if (i % 3 === 0) {
        const recv = await call('GET', `/receivables?customerId=${origin.customerId}&status=OPEN&page=1&size=50`).catch(() => null)
        const target = unwrap(recv).find((r) => String(r.salesOrderId) === String(origin.id) && Number(r.balance) > 0)
        if (target) {
          const amount = Math.min(Number(ret.totalAmount ?? 0), Number(target.balance))
          if (amount > 0) {
            await call('POST', '/customer-receipts', {
              customerId: origin.customerId,
              amount,
              bizDate: iso(day),
              remark: '退货退款核销',
              allocations: [{ receivableId: Number(target.id), amount }],
            }, at).then(() => { refunded++ }, (e) => note(`退款核销 原单 ${origin.orderNo}`, e))
          }
        }
      }
    } catch (e) {
      note(`退货 原单 ${origin.orderNo}`, e)
    }
  }
  console.log(`退货单：${ok} 张（其中 ${refunded} 张走了退款核销）`)
}

// ─────────────────────────── 库存作业 ───────────────────────────

/**
 * 按 (仓, SKU) 找一条**真实存在且有可用量**的库存行，连批次号一起返回。
 *
 * <p>为什么必须带批次：入库时的 receipt 会指定 {@code batchNo}（如 {@code B202609}），
 * 于是一个 SKU 在某仓的余额行是按批次分裂的。调拨、盘点如果拿 {@code batchNo: ''}
 * 去操作，后端会报 {@code SYS_NOT_FOUND 库存余额不存在} —— 空批次那一行根本没建过。
 * 这类失败在单据表上不留痕迹，只有库存作业页会空一片，很难反查。
 */
async function pickBalanceRow(warehouseId, minAvail = 4, beforeDay = null) {
  const rows = await fetchAll(`/inventory?warehouseId=${warehouseId}`, 200)
  return rows
    .filter((b) => Number(b.availableQty ?? 0) >= minAvail)
    .filter((b) => !beforeDay || receivedBefore(b.batchNo, beforeDay))
    .sort((a, b) => Number(b.availableQty) - Number(a.availableQty))[0] ?? null
}

async function runInventoryOps(products, warehouses) {
  const mainWh = warehouses.filter((w) => w.main)
  const shopWh = warehouses.filter((w) => w.main === false && w.name.includes('门店'))
  const rtWh = warehouses.find((w) => w.name.includes('退货'))

  // 调拨：主仓 → 门店仓，一半签收、一半留在在途（调拨在途是真实场景）
  // 候选必须从"该主仓自己的商品"里挑 —— 拿别仓的 SKU 来调，可用量恒为 0。
  let transferOk = 0
  let inTransit = 0
  for (let i = 0; i < 12; i++) {
    const from = mainWh[i % mainWh.length]
    const to = i % 4 === 3 && rtWh ? rtWh : (shopWh.length ? shopWh[i % shopWh.length] : rtWh)
    if (!to) continue
    // 落点用 opsMonth：调拨必须在铺货之后，否则成本回放时先出后进，整批重算 500
    const day = dayIn(opsMonth(6 + i).back, 3 + rnd(24))
    // ★ 直接读余额表拿真实批次：空批次的余额行不存在，调拨会 404。
    //   同时只挑「作业日之前已到货」的批次（批次月份严格更早），
    //   否则调拨出库会插在该批进货之前，成本池回放时数量不足直接抛错。
    const sourceRows = (await fetchAll(`/inventory?warehouseId=${from.id}`, 200))
      .filter((b) => Number(b.availableQty ?? 0) >= 8 && receivedBefore(b.batchNo, day))
    if (sourceRows.length === 0) continue
    const lines = []
    const seen = new Set()
    for (let k = 0; k < 4; k++) {
      const cand = sourceRows[(i * 11 + k * 37) % sourceRows.length]
      if (!cand) continue
      const key = `${cand.skuId}:${cand.batchNo ?? ''}`
      if (seen.has(key)) continue
      seen.add(key)
      lines.push({
        skuId: Number(cand.skuId),
        batchNo: cand.batchNo ?? '',
        qty: Math.min(4 + rnd(8), Math.floor(Number(cand.availableQty) / 2)),
        unitCostAmount: products.flatMap((p) => p.skus).find((s) => Number(s.id) === Number(cand.skuId))
          ? (products.find((p) => p.skus.some((s) => Number(s.id) === Number(cand.skuId)))?.cost ?? 1000)
          : 1000,
      })
    }
    if (lines.length === 0) continue
    const at = bizTime(day, 900 + i)
    try {
      const t = await call('POST', '/inventory/transfers', {
        fromWarehouseId: Number(from.id),
        toWarehouseId: Number(to.id),
        bizDate: iso(day),
        remark: `门店补货调拨 ${to.name}`,
        lines,
      }, at)
      for (const l of lines) {
        availAdd(l.skuId, Number(from.id), -l.qty)
        availAdd(l.skuId, Number(to.id), l.qty)
      }
      // 前 8 张签收，其余留在在途 —— 调拨在途量在库存维度报表里是真实字段
      if (i < 8) await call('POST', `/inventory/transfers/${t.id}/receive`, undefined, at)
      else inTransit++
      transferOk++
    } catch (e) {
      note(`调拨 ${from.name}→${to.name}`, e)
    }
  }
  console.log(`调拨单：${transferOk} 张（${transferOk - inTransit} 已签收，${inTransit} 在途）`)

  // 盘点：3 次。每次都给一两行造出差异 —— 全部对得上时后端会以
  // INV_STOCKTAKE_NO_DIFFERENCE 拒绝过账，那种"盘了等于没盘"的单在页面上也是废行。
  //
  // ★ 差异方向要挑「盘盈优先」：盘亏（countedQty < bookQty）在过账时走 decreaseOnHand，
  //   若该余额行已被预占（available < on_hand），调减会撞 INV_NEGATIVE_NOT_ALLOWED。
  //   真实业务也一样 —— 盘点发现少货时，先得确认那批货不是被订单占着的。
  //   所以这里绝大部分行做成盘盈（多出来），只在一行上做小幅盘亏且挑"没被预占"的行。
  const balances = await fetchAll('/inventory', 200)
  let stocktakeOk = 0
  let diffSheets = 0
  for (let i = 0; i < 3; i++) {
    const wh = mainWh[i % mainWh.length]
    const day = dayIn(opsMonth(5 + i).back, 3 + rnd(24))
    const rows = balances
      .filter((b) => Number(b.warehouseId) === Number(wh.id) && Number(b.onHandQty ?? 0) > 0)
      .slice(0, 40)
    if (rows.length === 0) continue
    // 找一行"没有预占/冻结"的，用来做盘亏；其余行做盘盈。
    // 盘亏会写出库流水，所以那一行还必须挑「盘点日之前已到货」的批次 ——
    // 盘某个还没到货的批次，成本池里没有数量可扣，重算当场失败。
    const freeRow = rows.find((b) => Number(b.reservedQty ?? 0) === 0
      && Number(b.frozenQty ?? 0) === 0
      && receivedBefore(b.batchNo, day))
    const lines = rows.map((b, idx) => {
      let counted = Number(b.onHandQty ?? 0)
      if (b === freeRow) counted = Math.max(0, counted - (2 + i)) // 盘亏 2~4 件
      else if (idx % 7 === 0) counted += 1 + (i % 3) // 盘盈 1~3 件
      return { skuId: Number(b.skuId), batchNo: b.batchNo ?? '', countedQty: counted }
    })
    const at = bizTime(day, 950 + i)
    try {
      const st = await call('POST', '/inventory/stocktakes', {
        warehouseId: Number(wh.id),
        bizDate: iso(day),
        remark: `月度盘点 ${wh.name}`,
        lines,
      }, at)
      await call('POST', `/inventory/stocktakes/${st.id}/post`, undefined, at)
      stocktakeOk++
      diffSheets++
    } catch (e) {
      note(`盘点 ${wh.name}`, e)
    }
  }
  console.log(`盘点单：${stocktakeOk} 张（其中 ${diffSheets} 张有差异并已过账）`)

  // 库存调整：报损 / 样品领用，给「调整」页面留几行真实的
  const adjustReasons = ['质检报损', '样品领用', '包装破损', '盘点调平']
  let adjustOk = 0
  for (let i = 0; i < 8; i++) {
    const wh = mainWh[i % mainWh.length]
    const day = dayIn(opsMonth(3 + i).back, 3 + rnd(24))
    // 必须带真实批次：调整走 decreaseOnHand，空批次行不存在会 INV_NEGATIVE_NOT_ALLOWED；
    // 且批次要早于作业日到货，否则调减会插在该批进货之前（成本池数量不足）
    const row = await pickBalanceRow(Number(wh.id), 4, day)
    if (!row) continue
    const cand = products.find((p) => p.skus.some((s) => Number(s.id) === Number(row.skuId)))
    try {
      await call('POST', '/inventory/adjustments', {
        skuId: Number(row.skuId),
        warehouseId: Number(wh.id),
        batchNo: row.batchNo ?? '',
        changeQty: -(1 + rnd(3)),
        reason: adjustReasons[i % adjustReasons.length],
        unitCostAmount: cand?.cost ?? 1000,
      }, bizTime(day, 1000 + i))
      adjustOk++
    } catch (e) {
      note(`库存调整 ${cand?.name ?? row.skuId}`, e)
    }
  }
  console.log(`库存调整：${adjustOk} 笔`)

  // 冻结：质检待判 / 客户预留，让「可用量 < 实存」这件事在页面上可见
  let freezeOk = 0
  for (let i = 0; i < 4; i++) {
    const wh = mainWh[i % mainWh.length]
    const row = await pickBalanceRow(Number(wh.id), 6)
    if (!row) continue
    const cand = products.find((p) => p.skus.some((s) => Number(s.id) === Number(row.skuId)))
    try {
      const day = dayIn(opsMonth(2 + i).back, 3 + rnd(24))
      await call('POST', '/inventory/freezes', {
        skuId: Number(row.skuId),
        warehouseId: Number(wh.id),
        batchNo: row.batchNo ?? '',
        qty: 2 + rnd(3),
        reason: i % 2 === 0 ? '质检待判定' : '客户预留未提',
        expiresAt: `${iso(new Date(day.getFullYear(), day.getMonth(), day.getDate() + 15))}T12:00:00`,
      }, bizTime(day, 1050 + i))
      freezeOk++
    } catch (e) {
      note(`库存冻结 ${cand?.name ?? row.skuId}`, e)
    }
  }
  console.log(`库存冻结：${freezeOk} 笔`)
}

/**
 * 安全库存阈值。
 *
 * 库里 min_level = 0 视为"未设下限"，这样的 SKU 永远不进库存预警 ——
 * 148 个 SKU 里只有 3 个有阈值时，预警页看起来就像坏了。
 * 这里给六成 SKU 设上下限，并把下限设在当前可用量之上，保证预警有内容。
 * PUT 是全量覆盖语义：barcode 省略会清空、enabled 省略会变 true，
 * 所以没打算改的字段要把原值带回去。
 */
async function ensureSafetyStock(products) {
  const flat = products.flatMap((p) => p.skus.map((s) => ({ s, p })))
  if (flat.length === 0) return
  const step = Math.max(1, Math.floor(flat.length / 90))
  let set = 0
  for (let i = 0; i < flat.length; i += step) {
    const { s, p } = flat[i]
    const have = availOf(Number(s.id), Number(p.warehouse.id))
    // 三条水位线各有用途，不要一律"下限设在库存之上"：
    //   ① 零库存的款：设下限，进「补货建议」，这是真实的待补货；
    //   ② 一成的款：下限压在库存之上 —— 这就是真实的缺货预警，页面上有内容；
    //   ③ 其余：下限取半仓 —— 预警页应该是安静的，只有异常才亮红灯。
    // 早先统一 `minLevel = have + 15 + ...`，603 个 SKU 里有 396 个长亮红灯，
    // 预警页从"风险清单"退化成"背景噪音"，等于没做预警。
    let minLevel
    let maxLevel
    if (have <= 0) {
      minLevel = 6 + (i % 6)
      maxLevel = 40
    } else if (i % 10 === 0) {
      minLevel = have + 8 + (i % 12)
      maxLevel = have + 80
    } else {
      minLevel = Math.max(2, Math.round(have * 0.5))
      maxLevel = Math.max(minLevel + 10, Math.round(have * 2))
    }
    await call('PUT', `/skus/${s.id}`, {
      barcode: s.barcode ?? '',
      retailPriceAmount: s.retailPriceAmount ?? p.retail,
      enabled: true,
      minLevel,
      maxLevel,
    }).then(() => { set++ }, (e) => note(`安全库存 ${p.name}`, e))
  }
  console.log(`安全库存阈值：已设 ${set} 个 SKU`)
}

// ─────────────────────────── 财务 / 营销 ───────────────────────────

async function runFinance(customers, products) {
  await settleWave()

  // 费用单：24 张，覆盖 6 个费用类型，大部分已付款（自动生成 6602/1002 凭证）
  const types = ['差旅费', '办公费', '业务招待费', '运输费', '培训费', '其他']
  const depts = ['市场部', '仓储物流部', '销售部', '行政部', '财务部']
  let expOk = 0
  for (let i = 0; i < 24; i++) {
    const day = dayBack(5 + i * 13)
    const at = bizTime(day, 1200 + i)
    // 金额走「元」口径（BigDecimal），与 sal/pur 的「分」不同，别混
    const amount = Math.round((80 + rnd(12000)) / 10) * 10 / 100 + 0.5
    try {
      let e = await call('POST', '/fin/expenses', {
        expenseType: types[i % types.length],
        departmentName: depts[i % depts.length],
        applyDate: iso(day),
        remark: `${types[i % types.length]}报销`,
        lines: [{ expenseItem: `${types[i % types.length]}-${depts[i % depts.length]}`, amount }],
      }, at)
      e = await call('POST', `/fin/expenses/${e.id}/submit`, undefined, at)
      e = await call('POST', `/fin/expenses/${e.id}/approve`, undefined, at)
      // 每四张留一张停在 APPROVED 未付款：待办与资金计划才有内容
      if (i % 4 !== 3) await call('POST', `/fin/expenses/${e.id}/pay`, undefined, at)
      expOk++
    } catch (err) {
      note(`费用单 ${types[i % types.length]}`, err)
    }
  }
  console.log(`费用单：${expOk} 张（其中 ${Math.floor(expOk * 3 / 4)} 张已付款）`)

  // 期间费用：房租 / 工资 / 物流 / 水电，按月计提并支付。
  //
  // ★ 为什么必须有这一段：只有上面那 24 张报销单时，利润表是
  //   "收入 1020 万、成本 650 万、管理费用几千块、净利 340 万"——
  //   净利率 33% 的贸易公司不存在，而且账上现金会一路堆到 300 多万。
  //   固定的期间费用（约 21 万/月）才是把毛利压成净利的关键一环，
  //   也解释得通"有利润，但现金没有堆成山"。
  //   金额按 5 个仓、40 个客户的规模给：工资 12 万、房租 4.5 万、物流 3.5 万、水电 1.2 万。
  const fixedCosts = [
    { type: '职工薪酬', dept: '行政部', amount: 120000 },
    { type: '房租物业', dept: '行政部', amount: 45000 },
    { type: '运输费', dept: '仓储物流部', amount: 35000 },
    { type: '水电费', dept: '行政部', amount: 12000 },
  ]
  let fixedOk = 0
  for (const month of MONTHS) {
    for (const [k, fc] of fixedCosts.entries()) {
      const day = dayIn(month.back, 5 + k)
      const at = bizTime(day, 1300 + k)
      try {
        let e = await call('POST', '/fin/expenses', {
          expenseType: fc.type,
          departmentName: fc.dept,
          applyDate: iso(day),
          remark: `${month.label} ${fc.type}`,
          lines: [{ expenseItem: `${month.label} ${fc.type}`, amount: fc.amount }],
        }, at)
        e = await call('POST', `/fin/expenses/${e.id}/submit`, undefined, at)
        e = await call('POST', `/fin/expenses/${e.id}/approve`, undefined, at)
        await call('POST', `/fin/expenses/${e.id}/pay`, undefined, at)
        fixedOk++
      } catch (err) {
        note(`期间费用 ${month.label} ${fc.type}`, err)
      }
    }
  }
  console.log(`期间费用：${fixedOk} 张（房租/工资/物流/水电 × ${MONTHS.length} 个月）`)

  // 促销：三种规则类型都在场，含一张已停用。
  // 注意：后端的规则 JSON 只存不解析（促销未接入算价链路），
  // 这里写 amountGte/reduce 是为了让页面上的规则描述像真的。
  const promos = [
    { code: 'DM-PRM-01', name: '双11 满1000减100', type: 'FULL_REDUCE', cond: '{"amountGte":100000}', benefit: '{"reduce":10000}', start: 340, end: -20 },
    { code: 'DM-PRM-02', name: '618 全场9折', type: 'TIME_DISCOUNT', cond: '{"always":true}', benefit: '{"discountBp":9000}', start: 200, end: 120 },
    { code: 'DM-PRM-03', name: '秋冬上新 3件8折', type: 'QTY_DISCOUNT', cond: '{"qtyGte":3}', benefit: '{"discountBp":8000}', start: 60, end: -40 },
    { code: 'DM-PRM-04', name: '会员日满500减50', type: 'FULL_REDUCE', cond: '{"amountGte":50000}', benefit: '{"reduce":5000}', start: 30, end: -10 },
    { code: 'DM-PRM-05', name: '春季清仓 7折', type: 'TIME_DISCOUNT', cond: '{"always":true}', benefit: '{"discountBp":7000}', start: 240, end: 150, stop: true },
  ]
  let promoOk = 0
  for (const [i, p] of promos.entries()) {
    try {
      const created = await call('POST', '/prm/promotions', {
        code: p.code,
        name: p.name,
        promoType: p.type,
        startTime: `${iso(dayBack(p.start))}T00:00:00`,
        endTime: `${iso(dayBack(p.end))}T23:59:59`,
        priority: i + 1,
        remark: '演示活动',
        rules: [{ conditionJson: p.cond, benefitJson: p.benefit }],
      }, `${iso(dayBack(p.start))}T10:00:00`)
      if (!p.stop) await call('POST', `/prm/promotions/${created.id}/activate`).catch((e) => note(`激活促销 ${p.code}`, e))
      promoOk++
    } catch (e) {
      note(`促销 ${p.code}`, e)
    }
  }
  console.log(`促销活动：${promoOk} 个`)

  // 价格方案：客户等级 1 张 + 单客户协议价 1 张 + 门店批发价 1 张
  const skus = products.flatMap((p) => p.skus.map((s) => ({ s, p }))).slice(0, 60)
  const keyCustomer = customers.find((c) => c.name === '城南百货') ?? customers[0]
  const plans = [
    { code: 'DM-PLN-01', name: '百货渠道年度协议价', type: 'AGREEMENT', customer: keyCustomer, bp: 8800 },
    { code: 'DM-PLN-02', name: 'A 级客户等级价', type: 'CUSTOMER_LEVEL', level: 'A', bp: 9000 },
    { code: 'DM-PLN-03', name: '门店常规批发价', type: 'CUSTOMER_LEVEL', level: 'B', bp: 9300 },
  ]
  let planOk = 0
  for (const [i, pl] of plans.entries()) {
    if ((pl.type === 'AGREEMENT' || pl.type === 'CUSTOMER') && !pl.customer) continue
    try {
      await call('POST', '/prm/price-plans', {
        code: pl.code,
        name: pl.name,
        planType: pl.type,
        // ★ AGREEMENT/CUSTOMER 必须给 customerId；CUSTOMER_LEVEL 必须给 customerLevel
        customerId: pl.type === 'CUSTOMER_LEVEL' ? null : pl.customer.id,
        customerLevel: pl.type === 'CUSTOMER_LEVEL' ? pl.level : null,
        priority: 10 - i,
        startDate: iso(dayBack(300)),
        endDate: iso(dayBack(-60)),
        enabled: true,
        remark: '演示价格方案',
        items: skus.map(({ s, p }) => ({
          skuId: Number(s.id),
          priceAmount: p.retail,
          discountBp: pl.bp,
          minQty: 1,
        })),
      })
      planOk++
    } catch (e) {
      note(`价格方案 ${pl.code}`, e)
    }
  }
  console.log(`价格方案：${planOk} 个`)
}

/**
 * 供应链闭环：请购 → 转采购单 → 到货质检 → 采购退货。
 *
 * <p>这段是"采购闭环"的证据链：请购单停在 DRAFT/SUBMITTED/APPROVED 各若干，
 * 转出去的那几张真的生成了采购单并入库（带质检不合格），
 * 采购退货挂真实到货行并冲减应付 —— 应付余额因此不是"入库金额的复读"。
 */
async function runSupplyChain(products, warehouses) {
  const mainWh = warehouses.filter((w) => w.main)
  // 批次级可用量：采购退货要按 **原收货批次** 扣减，扣的是"现在还剩多少"。
  // 只按已有单据反推（"这批还没退过"）是不够的 —— 批次可能已经被后面的销售卖空，
  // 那时 approve 会直接 INV_NEGATIVE_NOT_ALLOWED，退货单永远停在 DRAFT。
  const batchAvail = new Map()
  for (const b of await fetchAll('/inventory', 200)) {
    const k = `${b.skuId}:${b.warehouseId}:${b.batchNo ?? ''}`
    batchAvail.set(k, (batchAvail.get(k) ?? 0) + Number(b.availableQty ?? 0))
  }
  // 请购：12 张，状态铺全
  let reqOk = 0
  let converted = 0
  for (let i = 0; i < 12; i++) {
    const p = products[(i * 11 + 5) % products.length]
    const wh = mainWh[i % mainWh.length]
    const day = dayBack(8 + i * 15)
    const at = bizTime(day, 1300 + i)
    try {
      let req = await call('POST', '/purchase-requisitions', {
        supplierId: p.supplier.id,
        warehouseId: Number(wh.id),
        bizDate: iso(day),
        remark: `门店要货申请 ${p.name}`,
        lines: [{ skuId: Number(p.skus[0].id), qty: 30 + rnd(40), unitPriceAmount: p.cost }],
      }, at)
      if (i % 4 !== 0) req = await call('POST', `/purchase-requisitions/${req.id}/submit`, undefined, at)
      if (i % 4 === 2) req = await call('POST', `/purchase-requisitions/${req.id}/approve`, undefined, at)
      // 已审核的那批转成采购单，真正走到入库 —— 采购闭环在数据上连通
      if (i % 4 === 2 && req.status === 'APPROVED') {
        const po = await call('POST', `/purchase-requisitions/${req.id}/convert`, {
          bizDate: iso(day),
          remark: '请购转采购',
          lines: (req.lines ?? []).map((l) => ({ lineId: Number(l.id), qty: Number(l.qty) })),
        }, at)
        // 转出的采购单继续走 提交 → 审核 → 入库（带一点质检不合格）
        await call('POST', `/purchase-orders/${po.id}/submit`, undefined, at)
        const approved = await call('POST', `/purchase-orders/${po.id}/approve`, undefined, at)
        await receivePO(approved, Number(wh.id), at, { qcRatio: 0.06 })
        converted++
      }
      reqOk++
    } catch (e) {
      note(`请购 ${p.name}`, e)
    }
  }
  console.log(`请购单：${reqOk} 张（${converted} 张已转采购单并入仓）`)

  // 采购退货：挂真实到货行，冲减应付
  let retOk = 0
  const receipts = await fetchAll('/purchase-orders?status=COMPLETED&page=1&size=60', 60)
  // 从最近的进货单往回挑：越新的批次越可能还有余额，半年前的批次早被卖空了
  const candidates = [...receipts].reverse()
  for (let i = 0; i < candidates.length && retOk < 6; i++) {
    const po = candidates[i]
    try {
      const detail = await call('GET', `/purchase-orders/${po.id}`)
      const recvList = await call('GET', `/purchase-orders/${po.id}/receipts`).catch(() => null)
      const rec = unwrap(recvList)?.[0]
      const whId = Number(detail.warehouseId)
      // ★ 必须挑「这一批现在还留着货」的行：退货按原批次扣减，
      //   批次卖空后 approve 会 INV_NEGATIVE_NOT_ALLOWED，单子永远卡在 DRAFT。
      //   只判断"这张单还没退过"是不够的 —— 库存是按批次算的，不是按单算的。
      const line = (rec?.lines ?? []).find((l) => Number(l.qcQty ?? 0) >= 3
        && (batchAvail.get(`${l.skuId}:${whId}:${l.batchNo ?? ''}`) ?? 0) >= 3)
      if (!line) continue
      // ★ 退货日期必须晚于原进货单的业务日期。
      //   早先用 dayBack(6 + i * 11) 取"近 N 天"，会落到某张单的到货日之前：
      //   成本引擎按 occurred_at 回放时那一仓还没进货，直接抛
      //   "成本池数量不足，无法出库"，整批 /cost/recalculate 500，
      //   毛利与库存金额两张报表全空 —— 而单据表上看起来完全正常。
      const poDate = new Date(`${detail.bizDate}T12:00:00`)
      const back = new Date(poDate.getTime() + (1 + (i % 3)) * 86400000)
      const day = back > TODAY ? TODAY : back
      const at = bizTime(day, 1400 + i)
      const qty = Math.min(3, Number(line.qcQty))
      let pr = await call('POST', '/purchase-returns', {
        purchaseOrderId: Number(po.id),
        bizDate: iso(day),
        remark: '质检不合格批次退回供应商',
        lines: [{ receiptLineId: Number(line.id), qty }],
      }, at)
      // 采购退货的 approve 即"执行"：DRAFT → COMPLETED，中间没有 submit
      if (retOk === 3) {
        // 留一张 DRAFT 未执行，页面上"待处理退货"才有内容
        retOk++
        continue
      }
      pr = await call('POST', `/purchase-returns/${pr.id}/approve`, undefined, at)
      const bk = `${line.skuId}:${whId}:${line.batchNo ?? ''}`
      batchAvail.set(bk, Math.max(0, (batchAvail.get(bk) ?? 0) - qty))
      retOk++
    } catch (e) {
      note(`采购退货 单 ${po.id}`, e)
    }
  }
  console.log(`采购退货：${retOk} 张`)

  // 发票：销项/进项各若干，挂在真实单据上。
  //
  // ★ 三个约束叠在一起，随手取个「单据金额」就会失败：
  //   ① 单位不同：{@code sales_orders/purchase_orders.total_amount} 是**分**，
  //      而 {@code fin/invoices.amount} 是**元**；
  //   ② 后端校验「累计开票额（不含税）+ 本次 ≤ 单据金额」（FIN_INVOICE_OVER_AMOUNT）；
  //   ③ 同一张单据可能被跳过重取，若每次都命中同一单，第二次必然超额。
  //   所以：先记录已开过票的单据 id，跳过；金额按单据净额 85% 开，留出税与余量。
  let invOk = 0
  const invoiced = new Set()
  const netOf = (fen) => Math.floor(Number(fen) / 100 * 0.85 * 100) / 100
  for (let i = 0; i < 10; i++) {
    const isOutput = i % 2 === 0
    const day = dayBack(10 + i * 7)
    const at = bizTime(day, 1500 + i)
    try {
      if (isOutput) {
        const orders = await fetchAll('/sales-orders?status=COMPLETED&page=1&size=40', 40)
        const o = orders.find((x) => !invoiced.has(`SO:${x.id}`))
        if (!o) continue
        invoiced.add(`SO:${o.id}`)
        const cust = await call('GET', `/customers/${o.customerId}`)
        const net = netOf(o.totalAmount)
        await call('POST', '/fin/invoices', {
          invoiceNo: `FP-XS-${String(i + 1).padStart(4, '0')}`,
          type: 'OUTPUT',
          partnerId: Number(o.customerId),
          partnerName: cust.name,
          amount: net,
          taxAmount: Math.round(net * 0.13 * 100) / 100,
          invoiceDate: iso(day),
          sourceType: 'SALES_ORDER',
          sourceId: Number(o.id),
          remark: '销项发票',
        }, at)
      } else {
        const pos = await fetchAll('/purchase-orders?status=COMPLETED&page=1&size=40', 40)
        const po = pos.find((x) => !invoiced.has(`PO:${x.id}`))
        if (!po) continue
        invoiced.add(`PO:${po.id}`)
        const sup = await call('GET', `/suppliers/${po.supplierId}`)
        const net = netOf(po.totalAmount)
        await call('POST', '/fin/invoices', {
          invoiceNo: `FP-JX-${String(i + 1).padStart(4, '0')}`,
          type: 'INPUT',
          partnerId: Number(po.supplierId),
          partnerName: sup.name,
          amount: net,
          taxAmount: Math.round(net * 0.13 * 100) / 100,
          invoiceDate: iso(day),
          sourceType: 'PURCHASE_ORDER',
          sourceId: Number(po.id),
          remark: '进项发票',
        }, at)
      }
      invOk++
    } catch (e) {
      note('发票', e)
    }
  }
  console.log(`发票台账：${invOk} 张`)
}

/**
 * 成本引擎两步。
 *
 * 不跑这两步 cost_layers 是空的 —— 毛利分析整页无行，周转/呆滞报表也拿不到金额。
 * 月度成本按覆盖到的自然月逐月结，否则环比图只有最后一个月有数。
 */
async function runCostPipeline() {
  const rc = await call('POST', '/cost/recalculate', {}).catch((e) => note('成本重算', e))
  if (rc) {
    console.log(`成本重算：${rc.layerCount} 个成本层，期末成本 ${yuan(rc.endingCostAmount)} 元，配平=${rc.balanced}`)
  }

  // 结转销售成本要用到科目 id，先取一次
  const accounts = unwrap(await call('GET', '/fin/accounts'))
  const cogs = accounts.find((a) => a.code === '6401')
  const merchandise = accounts.find((a) => a.code === '1405')

  let carryTotal = 0
  let carryMonths = 0
  for (const [i, month] of MONTHS.entries()) {
    const cm = await call('POST', '/cost/monthly', { period: month.label })
      .catch((e) => note(`成本月结 ${month.label}`, e))
    if (!cm) continue
    console.log(`成本月结 ${month.label}：${cm.items?.length ?? 0} 个 SKU×仓，毛利记录 ${cm.marginRecordCount ?? 0} 条`)
    const carried = await postCostCarryForward(month, cm, cogs, merchandise, i)
    if (carried > 0) {
      carryTotal += carried
      carryMonths++
    }
  }
  if (carryMonths > 0) {
    console.log(`月末结转销售成本：${carryMonths} 个月，合计 ${yuan(carryTotal)} 元（借 6401 / 贷 1405）`)
  }
}

/**
 * 月末结转销售成本：借 6401 主营业务成本 / 贷 1405 库存商品。
 *
 * <p><b>为什么必须有这一步</b>：出货凭证（SAL_OUT）里的成本取自 cost_layers，
 * 而成本层是**批处理派生的** —— 出货那一刻还没有成本层，所以出货凭证只做了
 * 「借 1122 应收 / 贷 6001 收入」，成本侧是空的。
 * 不补结转，利润表上就是"收入 40 万、成本 0、毛利率 100%"，
 * 而库存商品科目会一路只进不出（账面 319 万 vs 成本引擎算出 137 万）。
 * 这在答辩现场是最容易被一眼看穿的地方：毛利率 100% 的公司不存在。
 *
 * <p>口径与做法都按"月末一次加权平均"：成本引擎按月结出各 SKU×仓 的出库成本，
 * 财务在月末做一张结转凭证。这是中小企业真实在用的做法，
 * 而不是把成本摊到每张出货单上（那需要出库当场就知道成本）。
 *
 * @return 本月结转金额（分），0 表示无出库或已跳过
 */
async function postCostCarryForward(month, cm, cogs, merchandise, idx) {
  if (!cogs || !merchandise) {
    if (idx === 0) note('成本结转', new Error('科目表里找不到 6401 / 1405'))
    return 0
  }
  const outboundFen = (cm.items ?? []).reduce((s, it) => s + Number(it.outboundCostAmount ?? 0), 0)
  if (outboundFen <= 0) return 0
  const amount = Math.round(outboundFen) / 100

  // 凭证日期取当月最后一天；当月没结束就取今天（不能开未来日期的账）
  const [y, m] = month.label.split('-').map(Number)
  const monthEnd = new Date(y, m, 0, 12)
  const day = monthEnd > TODAY ? TODAY : monthEnd
  const at = bizTime(day, 1200 + idx)
  try {
    const v = await call('POST', '/fin/vouchers', {
      voucherDate: iso(day),
      remark: `月末结转销售成本 ${month.label}`,
      lines: [
        { accountId: Number(cogs.id), summary: `结转销售成本 ${month.label}`, debit: amount, credit: 0 },
        { accountId: Number(merchandise.id), summary: `库存商品出库 ${month.label}`, debit: 0, credit: amount },
      ],
    }, at)
    await call('POST', `/fin/vouchers/${v.id}/audit`, undefined, at)
    await call('POST', `/fin/vouchers/${v.id}/post`, undefined, at)
    return outboundFen
  } catch (e) {
    note(`结转成本 ${month.label}`, e)
    return 0
  }
}

// ─────────────────────────── 中间态 ───────────────────────────

/**
 * 停在中间态的单据：待办中心、审批工作台、发货台要有东西可点。
 * 全推到 COMPLETED 的系统看起来像「跑完的批处理」，不像正在被用的系统。
 */
async function runInFlight(products, customers) {
  const flows = unwrap(await call('GET', '/approvals/flows'))
  const on = new Set(flows.filter((f) => f.enabled && (f.steps ?? []).length > 0).map((f) => f.bizType))
  await primeAvail()
  const today = Math.max(1, TODAY.getDate())

  let submitted = 0
  let toReceive = 0
  let toShip = 0

  for (let i = 0; i < 8; i++) {
    const p = products[(i * 5) % products.length]
    const sku = p.skus[i % p.skus.length]
    const day = dayIn(0, 1 + rnd(today))
    const at = bizTime(day, 1600 + i)
    try {
      let order = await call('POST', '/purchase-orders', {
        supplierId: p.supplier.id, warehouseId: p.warehouse.id,
        bizDate: iso(day),
        remark: '待审批：季度补货',
        lines: [{ skuId: Number(sku.id), qty: 10 + rnd(20), unitPriceAmount: p.cost }],
      }, at)
      order = await call('POST', `/purchase-orders/${order.id}/submit`, undefined, at)
      if (on.has('PURCHASE_ORDER')) await openApproval('PURCHASE_ORDER', order)
      submitted++
    } catch (e) {
      note('待审批进货单', e)
    }
  }

  for (let i = 0; i < 6; i++) {
    const p = products[(i * 3 + 1) % products.length]
    const sku = p.skus[i % p.skus.length]
    const day = dayIn(0, 1 + rnd(today))
    const at = bizTime(day, 1650 + i)
    try {
      let order = await call('POST', '/purchase-orders', {
        supplierId: p.supplier.id, warehouseId: p.warehouse.id,
        bizDate: iso(day),
        remark: '待收货：已审核未到仓',
        lines: [{ skuId: Number(sku.id), qty: 8 + rnd(16), unitPriceAmount: p.cost }],
      }, at)
      order = await call('POST', `/purchase-orders/${order.id}/submit`, undefined, at)
      if (on.has('PURCHASE_ORDER')) await approveViaFlow('PURCHASE_ORDER', order)
      await call('POST', `/purchase-orders/${order.id}/approve`, undefined, at)
      toReceive++
    } catch (e) {
      note('待收货进货单', e)
    }
  }

  for (let i = 0; i < 6; i++) {
    const c = customers[(i * 7) % customers.length]
    const p = products[(i * 9) % products.length]
    const sku = p.skus[i % p.skus.length]
    if (availOf(Number(sku.id), Number(p.warehouse.id)) < 20) continue
    const day = dayIn(0, 1 + rnd(today))
    const at = bizTime(day, 1700 + i)
    try {
      let order = await call('POST', '/sales-orders', {
        customerId: c.id, warehouseId: p.warehouse.id,
        bizDate: iso(day),
        remark: '待发货：已审核待出库',
        lines: [{ skuId: Number(sku.id), qty: 4 + rnd(8), unitPriceAmount: p.retail, discountRate: c.discountRate }],
      }, at)
      order = await call('POST', `/sales-orders/${order.id}/submit`, undefined, at)
      if (on.has('SALES_ORDER')) await approveViaFlow('SALES_ORDER', order)
      await call('POST', `/sales-orders/${order.id}/approve`, undefined, at)
      toShip++
    } catch (e) {
      note('待发货出货单', e)
    }
  }
  console.log(`中间态：待审批进货 ${submitted} 张 / 待收货进货 ${toReceive} 张 / 待发货出货 ${toShip} 张`)
}

// ─────────────────────────── 异常样本 ───────────────────────────

/**
 * 各类异常各留一份，答辩时「系统怎么处理异常」能直接点开看：
 * 缺货未发、负毛利、采购超期未到货、呆滞库存（盘点差异在 runInventoryOps 里）。
 */
async function runAnomalies(products, customers) {
  const flows = unwrap(await call('GET', '/approvals/flows'))
  const on = new Set(flows.filter((f) => f.enabled && (f.steps ?? []).length > 0).map((f) => f.bizType))
  const today = Math.max(1, TODAY.getDate())

  // 1) 缺货样本：用一张「把可用量占满」的已审核单把库存锁住。
  //    后端在建单那一刻就校验可用量（INV_INSUFFICIENT_STOCK），
  //    所以"数量超过可用"的单根本建不出来。真实缺货的样子是：
  //    这张单把可用量占尽了，后续订单发不出去，业务员只能等补货。
  let shortage = 0
  for (let i = 0; i < 3; i++) {
    const p = products[(products.length - 1 - i * 7 + products.length) % products.length]
    const sku = p.skus[i % p.skus.length]
    const wid = Number(p.warehouse.id)
    const have = availOf(Number(sku.id), wid)
    if (have < 6 || have > 90) continue
    const day = dayIn(0, 1 + rnd(today))
    const at = bizTime(day, 1800 + i)
    try {
      let order = await call('POST', '/sales-orders', {
        customerId: customers[(i * 13) % customers.length].id,
        warehouseId: p.warehouse.id,
        bizDate: iso(day),
        remark: '缺货：可用量已被本单占满，等待补货',
        lines: [{ skuId: Number(sku.id), qty: have, unitPriceAmount: p.retail, discountRate: 9000 }],
      }, at)
      order = await call('POST', `/sales-orders/${order.id}/submit`, undefined, at)
      if (on.has('SALES_ORDER')) await approveViaFlow('SALES_ORDER', order)
      await call('POST', `/sales-orders/${order.id}/approve`, undefined, at)
      availAdd(Number(sku.id), wid, -have) // 审核即预占，本地台账跟着记
      shortage++
    } catch (e) {
      note('缺货单', e)
    }
  }

  // 2) 负毛利：低于成本价出货，毛利报表会出现负值行
  let negative = 0
  for (let i = 0; i < 2; i++) {
    const p = products[(i * 21) % products.length]
    const sku = p.skus[0]
    const wid = Number(p.warehouse.id)
    if (availOf(Number(sku.id), wid) < 6) continue
    const day = dayBack(3 + i * 2)
    const at = bizTime(day, 1850 + i)
    try {
      let order = await call('POST', '/sales-orders', {
        customerId: customers[(i * 5 + 2) % customers.length].id,
        warehouseId: p.warehouse.id,
        bizDate: iso(day),
        remark: '清仓特价（低于成本）',
        lines: [{ skuId: Number(sku.id), qty: 5, unitPriceAmount: Math.round(p.cost * 0.8), discountRate: 10000 }],
      }, at)
      order = await call('POST', `/sales-orders/${order.id}/submit`, undefined, at)
      if (on.has('SALES_ORDER')) await approveViaFlow('SALES_ORDER', order)
      order = await call('POST', `/sales-orders/${order.id}/approve`, undefined, at)
      await shipSO(order, wid, '清仓特价（低于成本）', at)
      negative++
    } catch (e) {
      note('负毛利单', e)
    }
  }

  // 3) 采购超期未到货：30~45 天前就该到仓，仍是已审核
  let overdue = 0
  for (let i = 0; i < 2; i++) {
    const p = products[(i * 11 + 3) % products.length]
    const sku = p.skus[i % p.skus.length]
    const day = dayBack(32 + i * 6)
    const at = bizTime(day, 1900 + i)
    try {
      let order = await call('POST', '/purchase-orders', {
        supplierId: p.supplier.id, warehouseId: p.warehouse.id,
        bizDate: iso(day),
        remark: '超期未到货：供应商延期',
        lines: [{ skuId: Number(sku.id), qty: 20, unitPriceAmount: p.cost }],
      }, at)
      order = await call('POST', `/purchase-orders/${order.id}/submit`, undefined, at)
      if (on.has('PURCHASE_ORDER')) await approveViaFlow('PURCHASE_ORDER', order)
      await call('POST', `/purchase-orders/${order.id}/approve`, undefined, at)
      overdue++
    } catch (e) {
      note('超期未到货进货单', e)
    }
  }

  // 4) 超期应收：把几笔早就到期的应收留着不核销（90+ 天那一档不能是空的）。
  //    做法不是造新数据，而是**挑早期的已出货单**确认其应收仍未结清 —— 
  //    账龄分档因此有真实的 >90 天行，而不是靠调参数摆出来。
  const recv = await fetchAll('/receivables?status=OPEN&page=1&size=200', 200)
  const asOf = new Date(TODAY)
  let overdueAr = 0
  for (const r of recv) {
    const due = r.dueDate ? new Date(`${r.dueDate}T12:00:00`) : null
    if (!due) continue
    const days = Math.floor((asOf - due) / 86400000)
    if (days > 30) overdueAr++
  }

  // 5) 呆滞：整年只在上架时进过一次货、之后再没动销的款（铺货时天然产生）
  console.log(`异常样本：缺货 ${shortage} 张 / 负毛利 ${negative} 张 / 超期未到货 ${overdue} 张 / 超期应收 ${overdueAr} 笔`)
}

// ─────────────────────────── 收付款核销 ───────────────────────────

/**
 * 收付款核销：按账期结账，刻意留一小撮逾期未收/未付。
 *
 * <p><b>为什么不能"只核最近三个月"</b>：那样一年前的发票全部挂着未收，
 * 账龄报表 90 天以上一档占了九成（实测应收 39.5 万里 34.6 万都在 >90 天），
 * 一眼就看得出"这家公司没人催款"。真实经营是绝大多数按账期收回，
 * 只有少数客户拖账 —— 所以正确做法是<b>按期结掉绝大部分</b>，
 * 再按账龄分档各留几笔逾期样本，四档自然成金字塔，而不是倒三角。
 *
 * <p><b>核销日不能晚于今天</b>：到期末来日的发票按"今天已收"处理会造出
 * 未来日期的收款单，日记账会出现时间穿越；这里统一 clamp 到今天。
 */
async function settleWave() {
  let receipts = 0
  let payments = 0
  let receivedFen = 0
  let paidFen = 0

  const DAY = 86400000
  const addDays = (d, n) => new Date(d.getTime() + n * DAY)
  const clampToday = (d) => (d > TODAY ? TODAY : d)
  /** 到期日距今多少天（负数=未到期）。 */
  const agedBy = (dueStr) => {
    if (!dueStr) return null
    return Math.floor((TODAY - new Date(`${dueStr}T12:00:00`)) / DAY)
  }
  /**
   * 每个账龄档留 2 笔作逾期样本。
   *
   * <p><b>为什么必须按档留</b>：只留"最老的两笔"会让 31~60、61~90 两档被清空，
   * 账龄图的四根柱子塌成两根（实测 0-30 有值、31-90 全 0、>90 只有一笔），
   * 评委一眼就知道这个账龄是摆出来的，不是经营出来的。
   */
  const pickKept = (rows, ageOf) => {
    const kept = new Set()
    for (const [lo, hi] of [[31, 60], [61, 90], [91, 100000]]) {
      const inBand = rows
        .filter((r) => {
          const a = ageOf(r)
          return a !== null && a >= lo && a <= hi
        })
        .sort((a, b) => ageOf(b) - ageOf(a))
      for (const r of inBand.slice(0, 2)) kept.add(Number(r.id))
    }
    return kept
  }

  // ── 应收 ──
  const recv = (await fetchAll('/receivables', 400))
    .filter((r) => Number(r.balance) > 0 && r.status !== 'SETTLED')
  const agedAr = recv
    .filter((r) => (agedBy(r.dueDate) ?? -1) > 30)
    .sort((a, b) => String(a.dueDate).localeCompare(String(b.dueDate)))
  const keepAr = pickKept(agedAr, (r) => agedBy(r.dueDate))
  /** 按 客户 + 到期月份 归组成一张回款单：真实财务是月度集中核销，不是一票一单。 */
  const arGroups = new Map()
  for (const r of recv) {
    if (keepAr.has(Number(r.id))) continue
    const age = agedBy(r.dueDate)
    // 未到期的自然还挂着（账龄 0~30 档的合理基数），逾期但没被挑中的按账期已收
    if (age === null || age <= 30) continue
    const due = new Date(`${r.dueDate}T12:00:00`)
    const key = `${r.customerId}|${ym(due)}`
    if (!arGroups.has(key)) arGroups.set(key, [])
    arGroups.get(key).push({ r, due })
  }
  for (const rows of arGroups.values()) {
    const customerId = Number(rows[0].r.customerId)
    const lastDue = rows.reduce((m, x) => (x.due > m ? x.due : m), rows[0].due)
    const day = clampToday(addDays(lastDue, 1 + rnd(4)))
    const allocations = rows
      .map((x) => ({ receivableId: Number(x.r.id), amount: Math.round(Number(x.r.balance)) }))
      .filter((a) => a.amount > 0)
    if (allocations.length === 0) continue
    const amount = allocations.reduce((s, a) => s + a.amount, 0)
    await call('POST', '/customer-receipts', {
      customerId,
      amount,
      bizDate: iso(day),
      remark: '客户回款（按账期核销）',
      allocations,
    }, bizTime(day, 700 + receipts))
      .then(() => { receipts++; receivedFen += amount }, (e) => note(`收款 客户 ${customerId}`, e))
  }

  // ── 应付 ──
  const pay = (await fetchAll('/supplier-payables', 400))
    .filter((p) => Number(p.balance) > 0 && p.status !== 'PAID' && p.status !== 'SETTLED')
  // 应付没有 due_date（实测列为空），用业务日期 + 30 天账期推算
  const payDue = (p) => (p.bizDate ? iso(addDays(new Date(`${p.bizDate}T12:00:00`), 30)) : null)
  const agedAp = pay
    .filter((p) => (agedBy(payDue(p)) ?? -1) > 30)
    .sort((a, b) => String(a.bizDate).localeCompare(String(b.bizDate)))
  const keepAp = pickKept(agedAp, (p) => agedBy(payDue(p)))
  const apGroups = new Map()
  for (const p of pay) {
    if (keepAp.has(Number(p.id))) continue
    const age = agedBy(payDue(p))
    if (age === null || age <= 30) continue
    const due = new Date(`${payDue(p)}T12:00:00`)
    const key = `${p.supplierId}|${ym(due)}`
    if (!apGroups.has(key)) apGroups.set(key, [])
    apGroups.get(key).push({ p, due })
  }
  for (const rows of apGroups.values()) {
    const supplierId = Number(rows[0].p.supplierId)
    const lastDue = rows.reduce((m, x) => (x.due > m ? x.due : m), rows[0].due)
    const day = clampToday(addDays(lastDue, 1 + rnd(4)))
    const allocations = rows
      .map((x) => ({ payableId: Number(x.p.id), amount: Math.round(Number(x.p.balance)) }))
      .filter((a) => a.amount > 0)
    if (allocations.length === 0) continue
    const amount = allocations.reduce((s, a) => s + a.amount, 0)
    await call('POST', '/supplier-payments', {
      supplierId,
      amount,
      bizDate: iso(day),
      remark: '供应商付款（按账期核销）',
      allocations,
    }, bizTime(day, 760 + payments))
      .then(() => { payments++; paidFen += amount }, (e) => note(`付款 供应商 ${supplierId}`, e))
  }

  console.log(`核销：收款 ${receipts} 笔 ${yuan(receivedFen)} 元，付款 ${payments} 笔 ${yuan(paidFen)} 元`)
  return { receipts, payments, receivedFen, paidFen }
}

// ─────────────────────────── 审批 ───────────────────────────

async function openApproval(bizType, doc) {
  return call('POST', '/approvals', {
    bizType, bizId: doc.id, bizRefNo: doc.orderNo, amount: doc.totalAmount,
  })
}

/**
 * 建实例并按实际生成的 PENDING 节点逐级签掉，返回实例终态。
 * 不能硬写「签两次」：第二级节点带金额阈值（50 万分 = 5000 元），
 * 小额单的这一级是 SKIPPED，对它调 approve 会撞节点状态非法。
 */
async function approveViaFlow(bizType, doc) {
  const inst = await openApproval(bizType, doc)
  let cur = inst
  for (let guard = 0; guard < 6 && cur.status === 'PENDING'; guard++) {
    await call('POST', `/approvals/${inst.id}/approve`, { remark: '演示审批通过' })
    cur = await call('GET', `/approvals/${inst.id}`)
  }
  return cur.status
}

// ─────────────────────────── 期初建账 ───────────────────────────

/**
 * 期初建账：给「货币资金」补来路，并让首页卡片与资产负债表对上。
 *
 * 不做这一步：科目 1002 银行存款期初 0、实收资本期初 0，资产负债表上
 * 银行存款 = Σ收 − Σ付 会是负数，而财务首页「资金余额」卡片是另一个数。
 * 做法：科目期初 = 银行卡片合计 − 总账 1002 的凭证净额（对账时的标准倒挤），
 * 借 1002 / 贷 4001 记同一个数，资产负债表仍配平。幂等：非 0 则不动。
 */
async function openingBalances() {
  const accounts = unwrap(await call('GET', '/fin/accounts'))
  const cash = accounts.find((a) => a.code === '1002')
  const capital = accounts.find((a) => a.code === '4001')
  if (!cash || !capital) return note('期初建账', new Error('科目表里找不到 1002 / 4001'))
  if (Number(cash.initBalance) !== 0) {
    console.log(`科目 1002 期初已有 ${cash.initBalance} 元，保留既有人工建账`)
    return
  }

  const banks = unwrap(await call('GET', '/fin/bank-accounts?page=1&size=50'))
  const cardTotal = banks.reduce((s, b) => s + Number(b.balance), 0)
  const bs = await call('GET', `/fin/reports/balance-sheet?period=${NOW_YM}`)
  const cashRow = (bs.assets ?? []).find((a) => a.code === '1002')
  if (!cashRow) return note('期初建账', new Error('资产负债表里没有 1002 行'))
  const voucherNet = Number(cashRow.amount) - Number(cash.initBalance)
  const opening = Math.round((cardTotal - voucherNet) * 100) / 100
  if (opening <= 0) return note('期初建账', new Error(`倒挤出的期初不是正数：${opening}`))

  await call('PUT', `/fin/accounts/${cash.id}`, { initBalance: opening }).catch((e) => note('科目 1002 期初', e))
  await call('PUT', `/fin/accounts/${capital.id}`, { initBalance: opening }).catch((e) => note('科目 4001 期初', e))
  console.log(`期初建账：借 1002 银行存款 / 贷 4001 实收资本 各 ${fix(opening)} 元`)
}

// ─────────────────────────── 对账 ───────────────────────────

/**
 * 造完自己报数：这些正是界面上会被看的数字，对不上就是 seed 的问题。
 *
 * <p>比"数够了"更重要的是<b>形状</b>：报表中心 11 张报表要能看出经营，
 * 就得有月份跨度、有季节性起伏、有分档分布。因此这里不只看行数，
 * 还把关键曲线的首尾和峰值打出来 —— 曲线塌成一个点时一眼就能发现。
 */
async function verify() {
  console.log('\n── 与界面对账 ──')

  const dash = await call('GET', '/dashboard/summary').catch((e) => {
    note('工作台', e)
    return null
  })
  if (dash?.kpis) {
    console.log(`工作台 KPI：${Object.entries(dash.kpis).map(([k, v]) => `${k}=${v}`).join('  ')}`)
  }

  for (const path of [
    '/products', '/skus', '/customers', '/suppliers', '/warehouses',
    '/purchase-orders', '/sales-orders', '/sales-quotes', '/shipments', '/return-orders',
    '/purchase-requisitions', '/purchase-returns',
    '/inventory/transfers', '/inventory/stocktakes', '/inventory/adjustments', '/inventory/freezes',
    '/fin/expenses', '/fin/invoices', '/prm/promotions', '/prm/price-plans',
  ]) {
    const d = await call('GET', `${path}?page=1&size=1`).catch(() => null)
    if (d) console.log(`  ${path.slice(1)} = ${d.total ?? unwrap(d).length}`)
  }

  // ── 月度趋势：12 个月，每个月都要有单，且峰值月份要能对得上双11/618 ──
  const from = iso(dayIn(MONTHS.length - 1, 1))
  const byMonth = new Map()
  for (const month of MONTHS) byMonth.set(month.label, { purchase: 0, sales: 0, amount: 0 })
  const trendS = await call('GET', `/reports/sales?from=${from}&to=${iso(TODAY)}&groupBy=MONTH`).catch(() => null)
  for (const row of trendS?.items ?? []) {
    const key = String(row.key ?? row.label ?? '').slice(0, 7)
    const slot = byMonth.get(key)
    if (slot) {
      slot.sales += Number(row.orderCount ?? 0)
      slot.amount += Number(row.amount ?? 0)
    }
  }
  const trendP = await call('GET', `/reports/purchase?from=${from}&to=${iso(TODAY)}&groupBy=MONTH`).catch(() => null)
  for (const row of trendP?.items ?? []) {
    const key = String(row.key ?? row.label ?? '').slice(0, 7)
    const slot = byMonth.get(key)
    if (slot) slot.purchase += Number(row.orderCount ?? 0)
  }
  const emptyMonths = [...byMonth].filter(([, v]) => v.sales === 0)
  const peak = [...byMonth].sort((a, b) => b[1].amount - a[1].amount)[0]
  console.log(`\n月度曲线（区间 ${from} → ${iso(TODAY)}）：`)
  for (const [label, v] of byMonth) {
    const bar = '█'.repeat(Math.min(40, Math.round(v.amount / Math.max(1, peak[1].amount) * 40)))
    console.log(`  ${label}  进货 ${String(v.purchase).padStart(3)} 单  出货 ${String(v.sales).padStart(3)} 单  ${yuan(v.amount).padStart(12)} 元 ${bar}`)
  }
  if (emptyMonths.length > 0) {
    console.log(`  ⚠ ${emptyMonths.map(([k]) => k).join(', ')} 没有出货单 —— 曲线会有断点`)
  } else {
    console.log(`  ✓ ${MONTHS.length} 个月全部有单；峰值月 ${peak[0]}`)
  }

  // ── 账龄分档：四档都要有行，否则分档图只有一根柱子 ──
  const arAging = await call('GET', `/reports/aging?type=AR&asOf=${iso(TODAY)}`).catch(() => null)
  if (arAging?.summary) {
    const s = arAging.summary
    console.log(`应收账龄：伙伴 ${s.partnerCount} 家，余额 ${yuan(s.totalBalance)} 元`)
    console.log(`  0-30 天 ${yuan(s.bucket0)} / 31-60 天 ${yuan(s.bucket1)} / 61-90 天 ${yuan(s.bucket2)} / >90 天 ${yuan(s.bucket3)}`)
    if (Number(s.bucket3) <= 0) console.log('  ⚠ >90 天档为空：账龄长尾没有样本')
  }
  const apAging = await call('GET', `/reports/aging?type=AP&asOf=${iso(TODAY)}`).catch(() => null)
  if (apAging?.summary) {
    const s = apAging.summary
    console.log(`应付账龄：供应商 ${s.partnerCount} 家，余额 ${yuan(s.totalBalance)} 元（>90 天 ${yuan(s.bucket3)} 元）`)
  }

  // ── 周转 / 呆滞：有行、且呆滞档非空 ──
  const turnover = await call('GET', `/reports/inventory-turnover?from=${from}&to=${iso(TODAY)}&deadDays=30`).catch(() => null)
  const slow = await call('GET', `/reports/slow-moving?from=${from}&to=${iso(TODAY)}&days=90`).catch(() => null)
  if (turnover?.items) {
    const stale = turnover.items.filter((r) => r.stale).length
    console.log(`库存周转 ${turnover.items.length} 行（呆滞标记 ${stale} 行） / 90 天呆滞 ${slow?.items?.length ?? 0} 行`)
  }

  // ── 毛利：12 个月逐月有利润，且存在负毛利行（清仓样本）──
  const profit = await call('GET', `/reports/profit?from=${MONTHS[0].label}&to=${NOW_YM}&groupBy=period`).catch(() => null)
  if (profit?.items) {
    const negative = profit.items.filter((r) => Number(r.grossProfitAmount) < 0).length
    const months = profit.items.filter((r) => String(r.key ?? '').length === 7).length
    console.log(`毛利分析：${profit.items.length} 行（按月 ${months} 行，负毛利 ${negative} 行）`)
  }

  // ── 库存预警 / 补货建议 ──
  const alerts = unwrap(await call('GET', '/inventory/alerts').catch(() => []))
  const repl = unwrap(await call('GET', '/inventory/replenishment').catch(() => []))
  console.log(`库存预警 ${alerts.length} 条 / 补货建议 ${repl.length} 条`)

  // ── 账实一致：流水净和必须等于余额实存（不变量 INV-2）──
  const check = await call('GET', '/inventory/consistency-check').catch(() => null)
  if (check) {
    console.log(`账实一致性：${check.consistent ? '✓ 通过' : '✗ 存在差异'}（检查 ${check.checked ?? '?'} 行）`)
  }

  // ── 财务：资产负债表配平 + 资金余额 ──
  const bs = await call('GET', `/fin/reports/balance-sheet?period=${NOW_YM}`).catch(() => null)
  if (bs) {
    console.log(`资产负债表 ${NOW_YM}：资产 ${fix(bs.assetTotal)} / 负债 ${fix(bs.liabilityTotal)} / 权益 ${fix(bs.equityTotal)} → 差额 ${fix(bs.diff)}，配平=${bs.balanced}`)
  }
  const ov = await call('GET', '/fin/overview').catch(() => null)
  if (ov?.cards) {
    const c = ov.cards
    console.log(`财务首页：资金余额 ${fix(c.fundsBalance)} / 应收 ${fix(c.receivableTotal)} / 应付 ${fix(c.payableTotal)} / 本月收 ${fix(c.monthInflow)} 支 ${fix(c.monthOutflow)}`)
  }

  if (failed.length > 0) {
    console.log(`\n失败 ${failed.length} 笔（不影响其余数据）：`)
    for (const f of failed.slice(0, 15)) console.log('  ' + f)
    if (failed.length > 15) console.log(`  ...另 ${failed.length - 15} 条`)
  }
  console.log(`\n用时 ${elapsed()}。打开 http://127.0.0.1:3030/ 看工作台 / 报表中心 / 数据大屏 / 财务中心。`)
}

main().catch((e) => {
  console.error('中止：', e.message)
  if (e.detail) console.error(e.detail)
  process.exitCode = 1
})


