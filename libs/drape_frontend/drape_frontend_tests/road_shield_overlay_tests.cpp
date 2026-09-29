#include "testing/testing.hpp"

#include "drape/overlay_tree.hpp"

#ifdef OMIM_AUTO
namespace road_shield_overlay_tests
{
class Handle : public dp::OverlayHandle
{
public:
  Handle(uint32_t index, uint8_t rank, m2::RectD rect, uint64_t priority = 100)
    : OverlayHandle(dp::OverlayID({}, kml::kInvalidMarkId, {0, 0}, index), dp::Center, priority, 0, false)
    , m_rect(rect)
  {
    SetOverlayRank(rank);
  }
  bool Update(ScreenBase const &) override { return m_ready; }
  m2::RectD GetPixelRect(ScreenBase const &, bool) const override { return m_rect; }
  void GetPixelShape(ScreenBase const &, bool, Rects & rects) const override { rects.emplace_back(m_rect); }
  bool IsBound() const override { return true; }
  bool m_ready = true;

private:
  m2::RectD m_rect;
};

UNIT_TEST(RoadShieldOverlay_BackgroundRequiresVisibleText)
{
  ScreenBase screen(m2::RectI(0, 0, 1000, 1000), m2::AnyRectD(m2::RectD(-1, -1, 1, 1)));
  dp::OverlayTree tree(1.0);
  Handle background(1, dp::OverlayRank0, {480, 490, 520, 510});
  background.SetRequiredOverlayRank(dp::OverlayRank1);
  Handle text(1, dp::OverlayRank1, {490, 495, 510, 505});
  auto const place = [&](bool includeText)
  {
    tree.Clear();
    tree.StartOverlayPlacing(screen, 19);
    tree.Add(make_ref(&background));
    if (includeText)
      tree.Add(make_ref(&text));
    tree.EndOverlayPlacing();
  };
  place(false);
  TEST(!background.IsVisible(), ("No text layout means no empty background"));
  Handle underneath(3, dp::OverlayRank0, {490, 495, 510, 505}, 50);
  tree.Clear();
  tree.StartOverlayPlacing(screen, 19);
  tree.Add(make_ref(&background));
  tree.Add(make_ref(&underneath));
  tree.EndOverlayPlacing();
  TEST(underneath.IsVisible(), ("An incomplete shield must not displace other labels"));
  text.m_ready = false;
  place(true);
  TEST(!background.IsVisible(), ("Pending glyphs must hide the background"));
  text.m_ready = true;
  place(true);
  TEST(background.IsVisible() && text.IsVisible(), ());

  Handle rival(2, dp::OverlayRank0, {499, 499, 530, 530}, 200);
  tree.Clear();
  tree.StartOverlayPlacing(screen, 19);
  tree.Add(make_ref(&background));
  tree.Add(make_ref(&text));
  tree.Add(make_ref(&rival));
  tree.EndOverlayPlacing();
  TEST(rival.IsVisible(), ());
  TEST(!background.IsVisible() && !text.IsVisible(), ("Displacement hides the complete shield"));
}
}  // namespace road_shield_overlay_tests
#endif

namespace road_shield_overlay_tests
{
UNIT_TEST(RoadShieldOverlay_DefaultMarkIdIsNotExternal)
{
  TEST(!kml::IsExternalMarkId(kml::kInvalidMarkId), ("Map features use the invalid mark sentinel"));
  TEST(!kml::IsExternalMarkId(kml::kDebugMarkId), ());
  TEST(!kml::IsExternalMarkId(42), ());
  TEST(kml::IsExternalMarkId((kml::kExternalMarkGroupId << 60) | 42), ());
}
}  // namespace road_shield_overlay_tests
