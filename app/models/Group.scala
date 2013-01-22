package models

import models.base.AccessibleEntity
import models.base.Accessor
import models.base.NamedEntity
import models.base.Formable

import models.forms.{GroupF,UserProfileF}

case class Group(val e: Entity) extends NamedEntity with AccessibleEntity with Accessor with Formable[GroupF] {

  def to: GroupF = new GroupF(
    id = Some(e.id),
    identifier = identifier,
    name = e.stringProperty(GroupF.NAME).getOrElse(UserProfileF.PLACEHOLDER_TITLE),
    description = e.stringProperty(GroupF.DESCRIPTION)
  )
}

