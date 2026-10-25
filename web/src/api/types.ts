export type Role = 'SUPER_ADMIN' | 'ADMIN' | 'USER'

export interface UserView {
  id: number
  phone: string
  role: Role
  nickname: string | null
  avatarUrl: string | null
  bio: string | null
}

export interface ShopView {
  shopName: string
  logoUrl: string | null
  logoFileId: number | null
  description: string | null
  contact: string | null
  shippingAddress: string | null
}

export interface TokenResponse {
  accessToken: string
  expiresIn: number
  user: UserView
}

export interface ApiErrorBody {
  code: string
  message: string
}

export type SmsScene = 'REGISTER' | 'LOGIN'

export interface AdminUser {
  id: number
  phone: string
  role: Role
  active: boolean
  createdAt: string
}

export interface PageResponse<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface ScrollPage<T> {
  items: T[]
  nextCursor: number | null
  hasMore: boolean
}

export type AiFeedStatus = 'DISABLED' | 'PENDING' | 'READY' | 'FALLBACK'

export interface FeedPage {
  items: Product[]
  nextCursor: number | null
  hasMore: boolean
  aiStatus: AiFeedStatus
  aiBuildId: string | null
  feedBuildId: string | null
}

export interface FeedStatus {
  aiStatus: AiFeedStatus
  aiBuildId: string | null
  feedBuildId: string | null
}

export type AssistantAction = 'navigate' | 'tool' | 'answer' | 'clarify'

export interface AssistantReply {
  action: AssistantAction
  reply: string
  route: string | null
  routeLabel: string | null
  query: Record<string, string>
  traceId: string
}

export interface FeaturedSeckill {
  activityId: number
  productId: number
  name: string
  coverUrl: string | null
  price: number
  seckillPrice: number
  remaining: number
  endsAt: string
}

export type FeedEventType = 'EXPOSURE' | 'CLICK'
export type FeedAlgorithm = 'AI' | 'RULE'

export interface FeedEventItem {
  type: FeedEventType
  productId: number
  position: number
}

export interface FeedEventPayload {
  traceId: string
  buildId: string | null
  algorithm: FeedAlgorithm
  events: FeedEventItem[]
}

export interface FileView {
  id: number
  key: string
  url: string
  originalName: string
  size: number
  contentType: string
}

export type ProductStatus = 'DRAFT' | 'ON_SALE' | 'OFF_SHELF'
export type ProductType = 'PHYSICAL' | 'DIGITAL'

export interface Category {
  id: number
  name: string
  sortOrder: number
}

export interface Product {
  id: number
  name: string
  description: string
  type: ProductType
  price: number
  stock: number
  categoryId: number
  categoryName: string
  coverFileId: number | null
  coverUrl: string | null
  digitalFileId: number | null
  status: ProductStatus
  deletedAt: string | null
  createdAt: string
  reason?: string | null
  tags?: string[]
}

export interface CartItem {
  id: number
  productId: number
  productName: string
  productType: ProductType
  price: number
  quantity: number
  stock: number
  coverUrl: string | null
  available: boolean
}

export interface Address {
  id: number
  receiverName: string
  receiverPhone: string
  province: string
  city: string
  district: string
  detail: string
  isDefault: boolean
}

export interface AddressPayload {
  receiverName: string
  receiverPhone: string
  province: string
  city: string
  district: string
  detail: string
  isDefault: boolean
}

export type OrderStatus =
  | 'PENDING_PAYMENT'
  | 'PAID'
  | 'PACKING'
  | 'SHIPPED'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'REFUND_REQUESTED'
  | 'RETURN_REQUESTED'
  | 'RETURNING'
  | 'RETURN_SHIPPED'
  | 'REFUNDED'

export type RefundStatus =
  | 'PENDING'
  | 'APPROVED'
  | 'RETURNING'
  | 'RETURN_SHIPPED'
  | 'COMPLETED'
  | 'REJECTED'
  | 'WITHDRAWN'

export type RefundKind = 'REFUND' | 'RETURN'

export type OrderSource = 'NORMAL' | 'SECKILL'

export interface OrderItem {
  id: number
  productId: number
  productName: string
  productType: ProductType
  price: number
  quantity: number
  coverUrl: string | null
  digitalFileId: number | null
}

export interface Order {
  orderNo: string
  status: OrderStatus
  orderSource: OrderSource | null
  activityId: number | null
  totalAmount: number
  hasPhysical: boolean
  buyerPhone: string | null
  buyerNickname: string | null
  receiverName: string | null
  receiverPhone: string | null
  receiverAddress: string | null
  trackingNo: string | null
  createdAt: string
  paidAt: string | null
  packedAt: string | null
  shippedAt: string | null
  completedAt: string | null
  cancelledAt: string | null
  refundedAt: string | null
  refundAmount: number | null
  refundKind: RefundKind | null
  refundStatus: RefundStatus | null
  refundReason: string | null
  refundRequestedAt: string | null
  refundReviewedAt: string | null
  refundReviewRemark: string | null
  returnTrackingNo: string | null
  returnShippedAt: string | null
  receivedAt: string | null
  items: OrderItem[]
}

export interface DownloadInfo {
  url: string
  expiresInSeconds: number
}

export type SeckillStatus = 'DRAFT' | 'ACTIVE' | 'ENDED' | 'CANCELLED'

export interface SeckillSubmit {
  requestId: string
  status: string
}

export interface SeckillResult {
  requestId: string
  status: string
  orderNo?: string | null
  errorCode?: string | null
  errorMessage?: string | null
}

export interface SeckillActivity {
  id: number
  productId: number
  productName: string
  productType: ProductType
  productPrice: number
  seckillPrice: number
  stock: number
  soldCount: number
  remaining: number
  perUserLimit: number
  startAt: string
  endAt: string
  status: SeckillStatus
  activeNow: boolean
  createdAt: string
  updatedAt: string
}

export interface DigitalPart {
  partNumber: number
  url: string
}

export interface DigitalUploadInit {
  uploadId: string
  key: string
  partSize: number
  partCount: number
  parts: DigitalPart[]
}

export interface DigitalFile {
  id: number
  key: string
  originalName: string
  sizeBytes: number
  contentType: string
}

export interface ReusableImage {
  id: number
  originalName: string
  sizeBytes: number
  status: number
  deletedAt: string | null
  url: string
}

export interface ReusableDigitalFile {
  id: number
  originalName: string
  sizeBytes: number
  contentType: string
  status: number
  deletedAt: string | null
  createdAt: string
}

export interface AdminCategory {
  id: number
  name: string
  sortOrder: number
  status: number
  productCount: number
}
