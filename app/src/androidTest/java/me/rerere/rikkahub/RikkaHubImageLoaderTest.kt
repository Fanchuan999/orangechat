/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.SingletonImageLoader
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RikkaHubImageLoaderTest {
    @Test
    fun applicationOwnsTheCoilSingletonFactory() {
        val app = ApplicationProvider.getApplicationContext<RikkaHubApp>()

        assertTrue(app is SingletonImageLoader.Factory)
        assertSame(SingletonImageLoader.get(app), SingletonImageLoader.get(app))
    }
}
