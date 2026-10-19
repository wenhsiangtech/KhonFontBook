import { definePlugin } from '@halo-dev/ui-shared'
import { IconPlug } from '@halo-dev/components'
import { markRaw } from 'vue'

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/khon-font-book',
        name: 'KhonFontBook',
        component: () => import('./views/HomeView.vue'),
        meta: {
          title: 'KhonFontBook',
          searchable: true,
          permissions: ['khon-font-book:view'],
          menu: {
            name: 'KhonFontBook',
            group: '工具',
            icon: markRaw(IconPlug),
            priority: 0,
          },
        },
      },
    },
  ],
  extensionPoints: {},
})
