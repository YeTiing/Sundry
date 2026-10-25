import { createRouter, createWebHistory } from 'vue-router'
import AdminCategoriesView from '@/views/AdminCategoriesView.vue'
import AdminFeedMetricsView from '@/views/AdminFeedMetricsView.vue'
import AdminOrdersView from '@/views/AdminOrdersView.vue'
import AdminProductsView from '@/views/AdminProductsView.vue'
import AdminSeckillView from '@/views/AdminSeckillView.vue'
import AdminView from '@/views/AdminView.vue'
import AddressesView from '@/views/AddressesView.vue'
import CartView from '@/views/CartView.vue'
import CheckoutView from '@/views/CheckoutView.vue'
import FeedView from '@/views/FeedView.vue'
import HomeView from '@/views/HomeView.vue'
import LoginView from '@/views/LoginView.vue'
import OrdersView from '@/views/OrdersView.vue'
import ProductDetailView from '@/views/ProductDetailView.vue'
import ProductListView from '@/views/ProductListView.vue'
import RegisterView from '@/views/RegisterView.vue'
import SeckillBuyView from '@/views/SeckillBuyView.vue'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'feed',
      component: FeedView,
    },
    {
      path: '/products',
      name: 'products',
      component: ProductListView,
    },
    {
      path: '/products/:id',
      name: 'product',
      component: ProductDetailView,
    },
    {
      path: '/account',
      name: 'account',
      component: HomeView,
      meta: { requiresAuth: true },
    },
    {
      path: '/cart',
      name: 'cart',
      component: CartView,
      meta: { requiresAuth: true },
    },
    {
      path: '/addresses',
      name: 'addresses',
      component: AddressesView,
      meta: { requiresAuth: true },
    },
    {
      path: '/checkout',
      name: 'checkout',
      component: CheckoutView,
      meta: { requiresAuth: true },
    },
    {
      path: '/orders',
      name: 'orders',
      component: OrdersView,
      meta: { requiresAuth: true },
    },
    {
      path: '/seckill/:activityId',
      name: 'seckill-buy',
      component: SeckillBuyView,
      meta: { requiresAuth: true },
    },
    {
      path: '/admin',
      name: 'admin',
      component: AdminView,
      meta: { requiresAuth: true, requiresAdmin: true },
    },
    {
      path: '/admin/products',
      name: 'admin-products',
      component: AdminProductsView,
      meta: { requiresAuth: true, requiresAdmin: true },
    },
    {
      path: '/admin/categories',
      name: 'admin-categories',
      component: AdminCategoriesView,
      meta: { requiresAuth: true, requiresAdmin: true },
    },
    {
      path: '/admin/orders',
      name: 'admin-orders',
      component: AdminOrdersView,
      meta: { requiresAuth: true, requiresAdmin: true },
    },
    {
      path: '/admin/seckill',
      name: 'admin-seckill',
      component: AdminSeckillView,
      meta: { requiresAuth: true, requiresAdmin: true },
    },
    {
      path: '/admin/feed',
      name: 'admin-feed',
      component: AdminFeedMetricsView,
      meta: { requiresAuth: true, requiresAdmin: true },
    },
    {
      path: '/login',
      name: 'login',
      component: LoginView,
    },
    {
      path: '/register',
      name: 'register',
      component: RegisterView,
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/',
    },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.ready) {
    await auth.bootstrap()
  }
  if (to.meta.requiresAuth === true && !auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.requiresAdmin === true && !auth.isAdmin) {
    return { name: 'feed' }
  }
  // 已登录用户不再停留在登录/注册页
  if ((to.name === 'login' || to.name === 'register') && auth.isAuthenticated) {
    return { name: 'feed' }
  }
  return true
})

export default router
