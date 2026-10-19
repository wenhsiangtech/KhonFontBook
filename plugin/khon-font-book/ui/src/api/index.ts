import { axiosInstance } from '@halo-dev/api-client'
import { KhonFontBookV1alpha1ConsoleApi } from './generated'

export const fontsApi = new KhonFontBookV1alpha1ConsoleApi(undefined, '', axiosInstance)
