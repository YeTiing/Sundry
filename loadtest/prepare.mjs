import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { execFileSync } from 'node:child_process'

const BASE = process.env.BASE_URL || 'http://127.0.0.1:8080'
const USERS = Number(process.env.USERS || 200)
const STOCK = Number(process.env.STOCK || 50)
const PASSWORD = process.env.LOAD_PASSWORD || 'secret123'
const LOG_FILE = process.env.LOG_FILE || path.join(os.tmpdir(), 'sundry-backend.log')
const MYSQL = process.env.MYSQL_BIN || 'mysql'
const DB_USER = process.env.DB_USER || 'root'
const DB_PASSWORD = process.env.DB_PASSWORD || '123456'
const DB_NAME = process.env.DB_NAME || 'sundry'
const PHONE_PREFIX = '1370000'

async function request(method, url, body, token) {
  const response = await fetch(BASE + url, {
    method,
    headers: {
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  let parsed = null
  try {
    parsed = text ? JSON.parse(text) : null
  } catch {
    parsed = text
  }
  if (!response.ok) {
    throw new Error(`${method} ${url} -> ${response.status}: ${text}`)
  }
  return parsed
}

const post = (url, body, token) => request('POST', url, body, token)
const get = (url, token) => request('GET', url, undefined, token)

async function login(phone) {
  return post('/api/auth/login/password', { phone, password: PASSWORD })
}

function localIso(date) {
  const pad = (value) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
    + `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

function smsCodeFor(phone) {
  const content = fs.readFileSync(LOG_FILE, 'utf8')
  const pattern = new RegExp(`\\[dev-sms\\] phone=${phone} scene=REGISTER code=(\\d{6})`, 'g')
  let match
  let code = null
  while ((match = pattern.exec(content)) !== null) {
    code = match[1]
  }
  return code
}

async function register(phone) {
  try {
    return await login(phone)
  } catch {
    // 首次注册
  }
  await post('/api/auth/sms-code', { phone, scene: 'REGISTER' })
  let code = null
  for (let i = 0; i < 30 && !code; i++) {
    await new Promise((resolve) => setTimeout(resolve, 100))
    code = smsCodeFor(phone)
  }
  if (!code) {
    throw new Error(`没有从 ${LOG_FILE} 找到 ${phone} 的验证码`)
  }
  return post('/api/auth/register', { phone, code, password: PASSWORD })
}

function mysql(sql) {
  return execFileSync(
    MYSQL,
    [`-u${DB_USER}`, `-p${DB_PASSWORD}`, '-N', '-e', sql, DB_NAME],
    { encoding: 'utf8' },
  ).trim()
}

async function uploadDigitalFile(adminToken) {
  const init = await post('/api/admin/uploads/digital/init', {
    fileName: 'loadtest.zip',
    contentType: 'application/zip',
    sizeBytes: 1024,
  }, adminToken)
  const part = init.parts[0]
  const upload = await fetch(part.url, {
    method: 'PUT',
    body: Buffer.alloc(1024, 1),
  })
  if (!upload.ok) {
    throw new Error(`分片上传失败: ${upload.status}`)
  }
  return post('/api/admin/uploads/digital/complete', {
    uploadId: init.uploadId,
    key: init.key,
  }, adminToken)
}

async function main() {
  const admin = (await login('13800138000')).accessToken
  const categories = await get('/api/categories')
  const software = categories.find((category) => category.name === '软件')
  if (!software) {
    throw new Error('缺少“软件”分类')
  }

  const file = await uploadDigitalFile(admin)
  const product = await post('/api/admin/products', {
    name: `压测数字商品-${Date.now()}`,
    description: '压测用，可忽略',
    price: 100.00,
    categoryId: software.id,
    type: 'DIGITAL',
    digitalFileId: file.id,
    status: 'ON_SALE',
  }, admin)

  const now = new Date()
  const activity = await post('/api/admin/seckill/activities', {
    productId: product.id,
    seckillPrice: 1.00,
    stock: STOCK,
    startAt: localIso(new Date(now.getTime() - 60_000)),
    endAt: localIso(new Date(now.getTime() + 3_600_000)),
  }, admin)
  await post(`/api/admin/seckill/activities/${activity.id}/activate`, {}, admin)

  const firstPhone = PHONE_PREFIX + '0001'
  await register(firstPhone)
  const hash = mysql(`SELECT password_hash FROM app_user WHERE phone='${firstPhone}'`)
  if (!hash) {
    throw new Error('读取密码哈希失败')
  }

  const values = []
  for (let i = 2; i <= USERS; i++) {
    const phone = PHONE_PREFIX + String(i).padStart(4, '0')
    values.push(`('${phone}','${hash}','USER',1)`)
  }
  if (values.length > 0) {
    mysql(`INSERT INTO app_user (phone,password_hash,role,status) VALUES ${values.join(',')}`
      + ' ON DUPLICATE KEY UPDATE password_hash=VALUES(password_hash)')
  }

  const phones = []
  for (let i = 1; i <= USERS; i++) {
    phones.push(PHONE_PREFIX + String(i).padStart(4, '0'))
  }

  const tokens = []
  for (let start = 0; start < phones.length; start += 20) {
    const batch = phones.slice(start, start + 20)
    const result = await Promise.all(batch.map(async (phone) => {
      let lastError = null
      for (let attempt = 0; attempt < 3; attempt++) {
        try {
          return (await login(phone)).accessToken
        } catch (error) {
          lastError = error
          await new Promise((resolve) => setTimeout(resolve, 200))
        }
      }
      throw lastError
    }))
    tokens.push(...result)
  }

  const dir = path.join(process.cwd(), 'loadtest')
  fs.writeFileSync(path.join(dir, 'tokens.txt'), tokens.join('\n') + '\n')
  fs.writeFileSync(path.join(dir, 'activity.txt'), String(activity.id))
  console.log(JSON.stringify({
    activityId: activity.id,
    productId: product.id,
    stock: STOCK,
    users: tokens.length,
  }))
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
